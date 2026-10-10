package scenarios

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import obscura.client.v1.Client.ClientMessage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import dev.barrelmaker.obscura.kit.stores.FriendStatus

/**
 * The friend graph is the only source of display names.
 *
 * These tests drive genuine encrypted messages and require that an established
 * friend cannot rewrite its own record, and that a stranger cannot create an
 * accepted friendship through an unsolicited response.
 */
class FriendGraphIntegrityTests {

    companion object {
        private var serverUp = false
        @BeforeAll @JvmStatic fun setup() { serverUp = checkServer() }
    }

    private fun need() = assumeTrue(serverUp)

    @Test
    fun `rescanning an accepted friend is idempotent`() = runBlocking {
        need()

        val alice = registerAndConnect("fgi_rescan_alice")
        val bob = registerAndConnect("fgi_rescan_bob")
        becomeFriends(alice, bob)

        alice.befriend(bob.userId!!, bob.username!!)
        delay(500)

        assertEquals(
            FriendStatus.ACCEPTED,
            alice.friendList.value.find { it.userId == bob.userId }?.status,
        )
        assertEquals(
            FriendStatus.ACCEPTED,
            bob.friendList.value.find { it.userId == alice.userId }?.status,
        )

        alice.disconnect(); bob.disconnect()
    }

    @Test
    fun `an accepted friend cannot rename itself or reset its own status`() = runBlocking {
        need()

        val alice = registerAndConnect("fgi_alice")
        val mallory = registerAndConnect("fgi_mallory")
        becomeFriends(mallory, alice)
        delay(500)

        val malloryId = mallory.userId!!
        val storedBefore = alice.friendList.value.find { it.userId == malloryId }
        assertNotNull(storedBefore, "Mallory must be in Alice's graph before the attack")
        assertEquals(FriendStatus.ACCEPTED, storedBefore!!.status, "precondition: accepted")
        val realName = storedBefore.username

        // A well-formed second request from an established friend must not
        // rewrite the recipient's trusted graph.
        val spoof = ClientMessage.newBuilder()
            .setTimestamp(System.currentTimeMillis())
            .setFriendRequest(obscura.client.v1.friendRequest { username = "Mum" })
            .build()
        mallory.messenger.queueMessage(alice.deviceId!!, spoof, alice.userId!!)
        mallory.messenger.flushMessages()

        // Let Alice ingest it.
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline) {
            try { alice.waitForMessage(1_500) } catch (_: Exception) {}
            if (System.currentTimeMillis() > deadline - 6_000) break
        }
        delay(500)

        val after = alice.friendList.value.find { it.userId == malloryId }
        assertNotNull(after, "Mallory must still be in the graph")
        println("  stored name before='$realName' after='${after!!.username}' status=${after.status}")

        assertEquals(realName, after.username,
            "a peer MUST NOT be able to rewrite its trusted display name")
        assertNotEquals("Mum", after.username, "the payload-supplied name must be ignored for a known peer")
        assertEquals(FriendStatus.ACCEPTED, after.status,
            "a peer MUST NOT be able to reset its own status — that silently removes it from fan-out")

        alice.disconnect(); mallory.disconnect()
    }

    @Test
    fun `a stranger cannot become an accepted friend by sending an unsolicited acceptance`() = runBlocking {
        need()

        val alice = registerAndConnect("fgi_victim")
        val stranger = registerAndConnect("fgi_stranger")
        delay(500)

        // No friendship in either direction. The stranger fetches Alice's prekeys (public to any
        // authenticated user) and sends a FRIEND_ACCEPT claiming Alice accepted them.
        stranger.messenger.fetchPreKeyBundles(alice.userId!!)
        val forged = ClientMessage.newBuilder()
            .setTimestamp(System.currentTimeMillis())
            .setFriendAccept(obscura.client.v1.friendAccept {})
            .build()
        stranger.messenger.queueMessage(alice.deviceId!!, forged, alice.userId!!)
        stranger.messenger.flushMessages()

        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline) {
            try { alice.waitForMessage(1_500) } catch (_: Exception) {}
            if (System.currentTimeMillis() > deadline - 6_000) break
        }
        delay(500)

        val injected = alice.friendList.value.find { it.userId == stranger.userId }
        println("  alice's graph after unsolicited acceptance: ${alice.friendList.value.map { it.username to it.status }}")
        assertTrue(injected == null || injected.status != FriendStatus.ACCEPTED,
            "an unsolicited FRIEND_ACCEPT MUST NOT create an accepted friend — got $injected")

        alice.disconnect(); stranger.disconnect()
    }
}
