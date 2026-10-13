package scenarios

import dev.barrelmaker.obscura.kit.SubmissionStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** A user with two devices, both provisioned before the friendship. */
class MultiDeviceTests {

    @Test
    fun `every device of a user receives, and a sender's other devices get a self-sync`() = runBlocking {
        requireServer()
        val bob1 = registerAndConnect("md_bob", newClient(deviceName = "Bob Phone"))
        val bob2 = newClient(deviceName = "Bob Laptop")
        bob2.loginAndProvision(bob1.username!!, TEST_PASSWORD)
        bob2.connect()
        val alice = registerAndConnect("md_alice")
        becomeFriends(alice, bob1)

        sendText(alice, bob1, "hello both")
        for (bob in listOf(bob1, bob2)) {
            val row = bob.awaitContent("hello both")
            assertEquals(alice.deviceId, row.senderDeviceId)
        }
        assertEquals(setOf(bob1.deviceId, bob2.deviceId), alice.devices.devicesOf(bob1.userId!!).toSet())

        val report = sendText(bob1, alice, "from phone")
        assertEquals(listOf(bob2.deviceId), report.selfSync.map { it.deviceId })
        assertEquals(listOf(SubmissionStatus.ACCEPTED), report.selfSync.map { it.status })
        alice.awaitContent("from phone")
        assertEquals(bob1.deviceId, bob2.awaitContent("from phone").senderDeviceId)

        bob1.disconnect(); bob2.disconnect(); alice.disconnect()
    }
}
