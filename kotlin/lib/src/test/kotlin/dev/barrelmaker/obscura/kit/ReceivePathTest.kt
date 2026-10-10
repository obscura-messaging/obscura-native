package dev.barrelmaker.obscura.kit

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.barrelmaker.obscura.kit.db.ObscuraDatabase
import dev.barrelmaker.obscura.kit.stores.FriendStore
import dev.barrelmaker.obscura.kit.stores.FriendStatus
import dev.barrelmaker.obscura.kit.stores.InboxStore
import com.google.protobuf.ByteString
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import obscura.client.v1.Client
import obscura.client.v1.Client.ClientMessage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * The receive path, unit-tested.
 *
 * **Why this file exists.** Until now no unit test constructed an `ObscuraClient`, so
 * `routeMessage`, `inboxMessage`, `clampFutureTimestamp` and every `handle*` were reachable only
 * from the integration suite — which needs a live, correctly configured `obscura-server`. That is
 * exactly where every shipped defect has been: a signature verified against a key from the same
 * message, a redelivery that notified twice, a typing indicator accepted into a conversation the
 * sender is not in. None of those needs a server to demonstrate.
 *
 * The client is built over an in-memory driver and never connects: the constructor wires managers
 * and opens a database, and `GatewayConnection` does not touch a socket until `connect()`. The
 * handlers are `internal` so this can call them directly with a hand-built `ClientMessage`, which
 * is the same thing `routeMessage` does after a successful decrypt.
 */
class ReceivePathTest {

    private val selfUserId = "019f025a-0000-7de6-84b6-000000000001"
    private val selfDeviceId = "019f025a-0000-7de6-84b6-0000000000d1"
    private val peerUserId = "019f025a-0000-7de6-84b6-000000000002"

    /** An authenticated, connected-to-nothing client. */
    private fun newClient(): ObscuraClient {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ObscuraDatabase.Schema.create(driver)
        val client = ObscuraClient(ObscuraConfig(apiUrl = "https://obscura.invalid"), driver)
        client.restoreSession(
            token = "t", refreshToken = null, userId = selfUserId,
            deviceId = selfDeviceId, username = "self",
        )
        return client
    }

    private fun appEntry(entryId: String, model: String = "pix", timestamp: Long = 1_700_000_000_000) =
        ClientMessage.newBuilder()
            .setTimestamp(timestamp)
            .setAppEntry(obscura.client.v1.appEntry {
                this.model = model
                id = entryId
                this.timestamp = timestamp
                data = ByteString.copyFrom("payload".toByteArray())
            })
            .build()

    // ── Clamping a peer-supplied timestamp ────────────────────────────────────

    @Test
    fun `a past timestamp is stored unchanged`() {
        val c = newClient()
        assertEquals(1_700_000_000_000L, c.clampFutureTimestamp(1_700_000_000_000L))
    }

    /**
     * Without the cap a peer sets `sentAt` far ahead and wins every REPLACE conflict forever — the
     * tie-break can only order writes it can compare honestly.
     */
    @Test
    fun `a far-future timestamp is clamped to roughly now plus a minute`() {
        val c = newClient()
        val clamped = c.clampFutureTimestamp(Long.MAX_VALUE / 2)
        val cap = System.currentTimeMillis() + 60_000L
        assertTrue(clamped <= cap, "must not exceed the cap")
        assertTrue(clamped > cap - 5_000L, "must be the cap, not something far below it")
    }

    /**
     * **The documented cross-kit divergence, and the branch that had no test.**
     *
     * `AppEntry.timestamp` is proto3 `uint64`, which protobuf-java surfaces as a SIGNED Long, so a
     * peer sending >= 2^63 arrives here NEGATIVE and sails under any `minOf` cap. Swift compares in
     * UInt64 space and correctly yields the cap, so identical wire bytes stored roughly -9.2e18 on
     * Android and now+60s on iOS.
     */
    @Test
    fun `a uint64 timestamp above 2 to the 63 arrives negative and still clamps to the cap`() {
        val c = newClient()
        val asSigned = java.lang.Long.parseUnsignedLong("18446744073709551615") // 2^64 - 1
        assertTrue(asSigned < 0, "precondition: protobuf-java hands this back as a negative Long")

        val clamped = c.clampFutureTimestamp(asSigned)

        val cap = System.currentTimeMillis() + 60_000L
        assertTrue(clamped > 0, "a negative sentAt must never be stored — it sorts before everything")
        assertTrue(clamped <= cap && clamped > cap - 5_000L, "it must land on the cap, as Swift does")
    }

    // ── A redelivery must not notify twice ────────────────────────────────────

    /**
     * Persist-then-ack GUARANTEES redelivery, so this is a normal path. `Inbox.sq` says the dedupe
     * exists so a duplicate cannot "inflate the app's processing counts, and post a second
     * notification for a message the user already has" — which was false while the emits fired
     * unconditionally.
     */
    @Test
    fun `a redelivered envelope is stored once and reported as not-new`() = runBlocking {
        val c = newClient()
        val msg = appEntry("entry-1")

        val first = c.routeMessage(msg, peerUserId, "dev-peer", "env-1")
        val second = c.routeMessage(msg, peerUserId, "dev-peer", "env-1")

        assertTrue(first, "a fresh envelope must be announced to the app")
        assertFalse(second, "a redelivery must be absorbed silently — it is still acked, not notified")
        assertEquals(1L, InboxStore(c.db).depth())
    }

    @Test
    fun `a distinct envelope carrying the same entry is a separate row`() = runBlocking {
        val c = newClient()

        assertTrue(c.routeMessage(appEntry("entry-1"), peerUserId, "dev-peer", "env-1"))
        assertTrue(c.routeMessage(appEntry("entry-1"), peerUserId, "dev-peer", "env-2"))

        assertEquals(2L, InboxStore(c.db).depth(),
            "dedupe keys on the envelope id, not on the app's entry id")
    }

    @Test
    fun `an inbox row records the authenticated sender, never the payload`() = runBlocking {
        val c = newClient()
        FriendStore(c.db).add(peerUserId, "alice", FriendStatus.ACCEPTED)

        c.routeMessage(appEntry("entry-1"), peerUserId, "dev-peer", "env-1")

        val row = InboxStore(c.db).peek().single()
        assertEquals(peerUserId, row.senderUserId)
        assertEquals("dev-peer", row.senderDeviceId)
        assertEquals("APP_ENTRY", row.kind)
        assertEquals("pix", row.modelKey)
    }

    // ── DEVICE_ANNOUNCE ───────────────────────────────────────────────────────

    private fun announce(deviceIds: List<String>, timestamp: Long = 1_700_000_000_000) =
        ClientMessage.newBuilder()
            .setTimestamp(timestamp)
            .setDeviceAnnounce(obscura.client.v1.deviceAnnounce {
                for (id in deviceIds) {
                    devices.add(obscura.client.v1.deviceInfo {
                        this.id = id
                        name = "D"
                    })
                }
            })
            .build()

    @Test
    fun `an announce from a friend replaces the device list`() = runBlocking {
        val c = newClient()
        val friends = FriendStore(c.db)
        friends.add(peerUserId, "alice", FriendStatus.ACCEPTED)

        c.handleDeviceAnnounce(announce(listOf("dev-a")), peerUserId)

        assertEquals(listOf("dev-a"), friends.get(peerUserId)!!.devices.map { it.id })
    }

    @Test
    fun `an announce from an unknown user does not create a friend`() = runBlocking {
        val c = newClient()
        val friends = FriendStore(c.db)
        c.handleDeviceAnnounce(announce(listOf("dev-a")), peerUserId)
        assertNull(friends.get(peerUserId))
    }

    // ── TYPING_SIGNAL ─────────────────────────────────────────────────────────

    private fun typing(contextId: String, state: Client.TypingState) = ClientMessage.newBuilder()
        .setTimestamp(System.currentTimeMillis())
        .setTypingSignal(obscura.client.v1.typingSignal {
            this.contextId = contextId
            this.state = state
        }).build()

    @Test
    fun `a started typing signal is visible under its opaque context`() = runBlocking {
        val c = newClient()
        FriendStore(c.db).add(peerUserId, "alice", FriendStatus.ACCEPTED)
        c.handleTypingSignal(
            typing("thread-123", Client.TypingState.TYPING_STATE_STARTED),
            peerUserId,
            "dev-peer",
        )
        assertEquals(listOf("alice"), c.observeTyping("thread-123").first())
    }

    @Test
    fun `a stopped typing signal clears the sender device`() = runBlocking {
        val c = newClient()
        c.handleTypingSignal(
            typing("thread-123", Client.TypingState.TYPING_STATE_STARTED),
            peerUserId,
            "dev-peer",
        )
        c.handleTypingSignal(
            typing("thread-123", Client.TypingState.TYPING_STATE_STOPPED),
            peerUserId,
            "dev-peer",
        )
        assertEquals(emptyList<String>(), c.observeTyping("thread-123").first())
    }

    @Test
    fun `an unspecified typing state is ignored`() = runBlocking {
        val c = newClient()
        c.handleTypingSignal(
            typing("thread-123", Client.TypingState.TYPING_STATE_UNSPECIFIED),
            peerUserId,
            "dev-peer",
        )
        assertEquals(emptyList<String>(), c.observeTyping("thread-123").first())
    }
}
