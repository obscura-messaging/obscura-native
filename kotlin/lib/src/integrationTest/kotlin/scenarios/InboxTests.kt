package scenarios

import dev.barrelmaker.obscura.kit.ObscuraClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The durable inbox, end to end against a real server.
 *
 * `InboxStoreTest` covers the store's rules in isolation. This file covers the part that cannot be
 * faked: that a real APP_ENTRY, sent by a real peer over a real Signal session and delivered by the
 * real gateway, arrives as an inbox row with the right authenticated identity on it.
 *
 * The inbox is the durable receive path: it commits before acknowledgement and the app drains it.
 */
class InboxTests {

    // The inbox needs no schema; modelKey is an opaque namespace string.

    /** Send an entry the way obscura-pix does: the caller names the recipient. */
    private suspend fun sendStory(from: ObscuraClient, to: ObscuraClient, entryId: String, content: String) =
        from.send(
            recipientUserIds = listOf(to.userId!!),
            modelKey = "story",
            entryId = entryId,
            payload = org.json.JSONObject(mapOf("content" to content)).toString().toByteArray(),
        )

    @Test
    fun `a received APP_ENTRY lands in the inbox with authenticated identity`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("inbox_a")
        val bob = registerAndConnect("inbox_b")
        becomeFriends(alice, bob)

        assertEquals(0L, bob.inbox.depth(), "inbox starts empty")

        sendStory(alice, bob, "story_7557", "hello from the wire")
        delay(3000)

        val rows = bob.inbox.peek()
        assertEquals(1, rows.size, "exactly one inbox row for one APP_ENTRY")
        val row = rows[0]

        assertEquals("APP_ENTRY", row.kind)
        assertEquals("story", row.modelKey, "modelKey is carried opaquely so the app can merge")
        // Identity comes from the envelope and the Signal session, never from the payload.
        assertEquals(alice.userId, row.senderUserId)
        assertEquals(alice.deviceId, row.senderDeviceId,
            "senderDeviceId is the address of the session that decrypted — cryptographic attribution")
        assertTrue(row.payload.isNotEmpty(), "payload is the opaque model data")

        alice.disconnect(); bob.disconnect()
    }

    /**
     * **A peer-supplied timestamp is clamped BEFORE it is stored.**
     *
     * `ReceivePathTest` covers the function directly, including negative protobuf `uint64` values;
     * this test proves the clamp is reached on the wire path.
     *
     * Without the clamp a peer sets `sentAt` far in the future and wins every REPLACE conflict
     * forever: the tie-break can only order writes it can compare honestly.
     *
     * `Long.MAX_VALUE` also exercises the subtle half. `AppEntry.timestamp` is proto3 `uint64`,
     * which protobuf-java surfaces as a SIGNED Long — so a large enough value arrives NEGATIVE and
     * sails under a naive `minOf` cap. Both ends are clamped.
     */
    @Test
    fun `a far-future peer timestamp is clamped before it is stored`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("clamp_a")
        val bob = registerAndConnect("clamp_b")
        becomeFriends(alice, bob)

        val before = System.currentTimeMillis()
        alice.send(
            recipientUserIds = listOf(bob.userId!!),
            modelKey = "story",
            entryId = "story_clamped",
            sentAt = Long.MAX_VALUE,
            payload = """{"content":"from the far future"}""".toByteArray(),
        )
        delay(3000)

        val row = bob.inbox.peek().find { it.entryId == "story_clamped" }
        assertTrue(row != null, "the entry should still arrive — clamping is not rejection")
        assertTrue(row!!.sentAt!! <= before + 120_000,
            "sentAt must be clamped to about now, not stored as ${row.sentAt}")
        assertTrue(row.sentAt!! > 0, "a signed-Long overflow must not store a negative timestamp")

        alice.disconnect(); bob.disconnect()
    }

    /**
     * Offline delivery is the whole point of a durable inbox: the server holds what it could not
     * deliver and hands it over on reconnect.
     *
     * This is also the case the drain's cold-start trigger exists for — a message that arrives while
     * the app is not listening must still reach the app.
     */
    @Test
    fun `a message sent while the recipient is offline arrives after reconnect`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("inboxoff_a")
        val bob = registerAndConnect("inboxoff_b")
        becomeFriends(alice, bob)

        bob.disconnect()
        delay(500)

        alice.send(
            recipientUserIds = listOf(bob.userId!!),
            modelKey = "directMessage",
            entryId = "dm_offline",
            payload = """{"content":"You there?"}""".toByteArray(),
        )
        delay(500)

        bob.connect()
        delay(4000)

        val rows = bob.inbox.peek()
        assertEquals(1, rows.size, "the server held it and delivered it on reconnect")
        assertEquals("dm_offline", rows[0].entryId)
        assertEquals("""{"content":"You there?"}""", String(rows[0].payload))

        alice.disconnect(); bob.disconnect()
    }

    /**
     * The full drain, which is the thing the app will actually do: peek, process, consume, and the
     * depth returns to zero. Also pins that consume is what empties it — nothing else does, because
     * once acked this row is the only copy of the message anywhere.
     */
    @Test
    fun `the app drains the inbox with peek then consume`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("inboxdrain_a")
        val bob = registerAndConnect("inboxdrain_b")
        becomeFriends(alice, bob)

        sendStory(alice, bob, "story_one", "one")
        delay(1500)
        sendStory(alice, bob, "story_two", "two")
        delay(3000)

        assertEquals(2L, bob.inbox.depth())

        val batch = bob.inbox.peek()
        assertEquals(2, batch.size)
        // Drain order is arrival order.
        assertTrue(batch[0].id < batch[1].id)

        // Processing half is normal — partial progress must be accepted.
        bob.inbox.consume(listOf(batch[0].id))
        assertEquals(1L, bob.inbox.depth())

        bob.inbox.consume(listOf(batch[1].id))
        assertEquals(0L, bob.inbox.depth())

        alice.disconnect(); bob.disconnect()
    }

    /**
     * **No end-to-end redelivery test:** producing a genuine redelivery requires
     * suppressing the first ACK, and `GatewayConnection` has no such seam.
     * The deduplication property is covered at the storage boundary:
     * `InboxStoreTest.a redelivered envelope does not create a second row` calls `put` twice with
     * one envelope id and asserts one row.
     *
     * Add the seam and test together if end-to-end coverage is required.
     */
}
