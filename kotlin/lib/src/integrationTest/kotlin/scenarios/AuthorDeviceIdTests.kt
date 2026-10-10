package scenarios

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * `authorDeviceId` comes from `Envelope.sender_device_id`, stamped from the
 * sender's device-scoped token and verified by the Signal session that decrypts.
 *
 * This test asserts that a message Alice receives from Bob is attributed to Bob's REAL DEVICE
 * UUID — both on the wake-up (`MessageWakeEvent.senderDeviceId`) and on the durably persisted
 * inbox row (`InboxRecord.senderDeviceId`) — and that it is emphatically NOT Bob's user id.
 */
class AuthorDeviceIdTests {

    companion object {
        private var serverUp = false
        @BeforeAll @JvmStatic fun setup() { serverUp = checkServer() }
    }

    private fun need() = assumeTrue(serverUp)

    @Test
    fun `received message authorDeviceId equals the sender's real device UUID, never the userId`() = runBlocking {
        need()

        val alice = registerAndConnect("adid_alice")
        val bob = registerAndConnect("adid_bob")
        becomeFriends(bob, alice)
        delay(500)

        val bobDeviceId = bob.deviceId!!
        val bobUserId = bob.userId!!
        assertNotEquals(bobDeviceId, bobUserId, "device UUID and user UUID must differ for this test to mean anything")

        sendAndVerify(bob, alice, "attribute me correctly")

        val received = alice.waitForMessage(15_000)
        assertEquals("attribute me correctly", received.content())
        assertEquals(bobUserId, received.sourceUserId, "sourceUserId is Bob's USER id")

        // The wake-up carries the sender's real DEVICE UUID (from Envelope.sender_device_id,
        // validated by the decrypting session's MAC) — not the user id.
        assertEquals(bobDeviceId, received.senderDeviceId,
            "senderDeviceId must be Bob's REAL device UUID")
        assertNotEquals(bobUserId, received.senderDeviceId,
            "senderDeviceId must not be the user id")

        delay(500)

        // The DURABLY PERSISTED record carries the honest device id too — and THIS is the assertion
        // that matters, because the wake-up above is droppable while the row is the delivery path.
        //
        // `senderDeviceId` on the row is the address of the Signal session that decrypted it.
        val row = alice.inbox.peek(200).find {
            org.json.JSONObject(String(it.payload)).optString("content", "") == "attribute me correctly"
        }
        assertNotNull(row, "message must be persisted in Alice's inbox")
        assertEquals(bobDeviceId, row!!.senderDeviceId,
            "persisted senderDeviceId must be Bob's REAL device UUID")
        assertNotEquals(bobUserId, row.senderDeviceId,
            "persisted senderDeviceId must not be the user id")

        println("PROVEN: senderDeviceId=${row.senderDeviceId} == bob.deviceId=$bobDeviceId " +
            "(bob.userId=$bobUserId)")

        alice.disconnect(); bob.disconnect()
    }
}
