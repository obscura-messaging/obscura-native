package scenarios

import dev.barrelmaker.obscura.kit.stores.FriendStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The message's user comes from the envelope (`sender_id`), and its display
 * name comes from the recipient's friend graph, never the payload.
 *
 * Signal-aligned: the Envelope carries BOTH the sending user (source_service_id -> sender_id, a
 * routing/attribution HINT) and the sending device (source_device -> sender_device_id, which selects
 * the inbound Signal session). The recipient takes the user from sender_id and authenticates it via
 * the session (a valid MAC proves the sender's device). It looks up the display name in its own
 * friend graph keyed on that user id; it never trusts a name off the wire. The ONE exception is a
 * friend request — first contact, sender not yet in the graph — where the display name is the
 * legitimate FriendRequest.username payload bootstrap.
 */
class IdentityFromEnvelopeTests {

    @Test
    fun `received text takes its user from envelope sender_id and its display name from the graph`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("ide_a")
        val bob = registerAndConnect("ide_b")
        becomeFriends(alice, bob)

        sendAndVerify(alice, bob, "user from envelope, name from graph")
        val msg = bob.waitForMessage(15_000)

        // (1) The message's USER is envelope.sender_id — server-stamped as Alice's real userId and
        // authenticated by the Signal session that decrypted (proven by the MAC on Alice's device).
        assertEquals("APP_ENTRY", msg.type)
        assertEquals(alice.userId, msg.sourceUserId,
            "the message's user must be envelope.sender_id (Alice's real userId)")
        assertEquals(alice.deviceId, msg.senderDeviceId,
            "senderDeviceId must be Alice's real device UUID (proven by the session that decrypted)")

        // (2) The DISPLAY NAME is NOT on a APP_ENTRY payload — it is empty here. The only
        // place Bob has Alice's name is his FRIEND GRAPH, keyed on the envelope's user id.
        assertEquals("", msg.username,
            "application payloads carry no display name; the name must come from the graph")
        val aliceInGraph = bob.friendList.value.firstOrNull { it.userId == alice.userId }
        assertNotNull(aliceInGraph, "Bob's friend graph must contain Alice keyed on her envelope user id")
        assertEquals(alice.username, aliceInGraph!!.username,
            "the display name comes from Bob's friend graph, keyed on envelope.sender_id")


        alice.disconnect(); bob.disconnect()
    }

    @Test
    fun `a friend request is attributed to envelope sender_id and bootstraps its display name from the payload`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("ide_fr_a")
        val bob = registerAndConnect("ide_fr_b")

        // Alice sends a friend request. Bob attributes the requester to envelope.sender_id (Alice's
        // real userId, authenticated by the session — TOFU pins her identity key on first contact),
        // and takes the DISPLAY NAME from the FriendRequest.username payload: the one legitimate
        // bootstrap, because Alice is not yet in Bob's graph.
        alice.befriend(bob.userId!!, bob.username!!)
        val req = bob.waitForType("FRIEND_REQUEST", 15_000)
        assertEquals(alice.userId, req.sourceUserId,
            "the friend request's user must be envelope.sender_id (Alice's real userId)")
        assertEquals(alice.username, req.username,
            "the friend request's display name is the FriendRequest.username payload bootstrap")

        kotlinx.coroutines.delay(500) // let the friend-list StateFlow refresh after persist
        val pending = bob.friendList.value.firstOrNull {
            it.userId == alice.userId && it.status == FriendStatus.PENDING_RECEIVED
        }
        assertNotNull(pending, "Bob's pending requests must list Alice under her envelope user id")
        assertEquals(alice.username, pending!!.username,
            "the pending request's display name is bootstrapped from the payload username")

        alice.disconnect(); bob.disconnect()
    }
}
