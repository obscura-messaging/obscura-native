package scenarios

import dev.barrelmaker.obscura.kit.AuthState
import dev.barrelmaker.obscura.kit.ConnectionState
import dev.barrelmaker.obscura.kit.ObscuraClient
import dev.barrelmaker.obscura.kit.ObscuraConfig
import dev.barrelmaker.obscura.kit.stores.FriendStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Persistence: file-backed DB survives simulated restart, queued messages
 * are drained, Signal sessions still work after restore.
 * All E2E against live server using ObscuraClient public API only.
 */
class PersistenceTests {

    @Test
    fun `Messages received after restart with file-backed DB`() = runBlocking {
        assumeTrue(checkServer())

        val bobDbPath = File.createTempFile("obscura_bob_", ".db").absolutePath

        // Alice: in-memory, stays alive
        val alice = registerAndConnect("pe_a")
        assertEquals(AuthState.AUTHENTICATED, alice.authState.value)

        // Bob: file-backed DB
        val bob1 = ObscuraClient(ObscuraConfig(API, databasePath = bobDbPath))
        bob1.register(uniqueName("pe_b"), TEST_PASSWORD)
        assertEquals(AuthState.AUTHENTICATED, bob1.authState.value)
        assertNotNull(bob1.userId)
        assertNotNull(bob1.deviceId)
        bob1.connect()
        assertEquals(ConnectionState.CONNECTED, bob1.connectionState.value)

        // Save Bob's credentials for restore
        val bobToken = bob1.token!!
        val bobRefreshToken = bob1.refreshToken
        val bobUserId = bob1.userId!!
        val bobDeviceId = bob1.deviceId
        val bobUsername = bob1.username

        // Establish friendship with state verification
        becomeFriends(alice, bob1)
        assertTrue(alice.friendList.value.any { it.userId == bobUserId && it.status == FriendStatus.ACCEPTED })
        assertTrue(bob1.friendList.value.any { it.userId == alice.userId && it.status == FriendStatus.ACCEPTED })

        // Exchange a message to prove sessions work
        sendAndVerify(alice, bob1, "before restart")


        // Bob disconnects (simulates app killed)
        bob1.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, bob1.connectionState.value)

        // Alice sends while Bob is offline — server queues it. `sendOnly`, not `sendAndVerify`:
        // the latter polls the receiver's inbox, which cannot fill until Bob reconnects.
        sendOnly(alice, bob1, "while you were gone")
        delay(1000)

        // Simulate restart: new ObscuraClient, same file-backed DB
        val bob2 = ObscuraClient(ObscuraConfig(API, databasePath = bobDbPath))
        bob2.restoreSession(
            token = bobToken,
            refreshToken = bobRefreshToken,
            userId = bobUserId,
            deviceId = bobDeviceId,
            username = bobUsername,
        )

        // Connect — should drain the server queue
        bob2.connect()
        assertEquals(ConnectionState.CONNECTED, bob2.connectionState.value,
            "Bob2 should be CONNECTED after restore + connect")

        // Receive the queued message
        val received = bob2.waitForType("APP_ENTRY", 20_000)
        assertEquals("while you were gone", received.content())
        assertEquals(alice.userId, received.sourceUserId)
        // THE assertion this test is named for: it survived a restart into a durable STORE, not
        // merely into the droppable in-process channel.
        assertTrue(bob2.hasReceived("while you were gone"),
            "the queued message must be in the restarted client's durable inbox")
        delay(300)


        // Prove bidirectional works after restart
        sendAndVerify(bob2, alice, "I'm back")
        val reply = alice.waitForType("APP_ENTRY")
        assertEquals("I'm back", reply.content())
        assertEquals(bobUserId, reply.sourceUserId)
        delay(300)


        alice.disconnect(); bob2.disconnect()
    }
}
