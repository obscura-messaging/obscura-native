package scenarios

import dev.barrelmaker.obscura.kit.SubmissionStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A user with two devices, both provisioned before the friendship. */
class MultiDeviceTests {
    @Test
    fun `late independent installation self syncs on next own send and peers learn only from its traffic`() = runBlocking {
        requireServer()
        val bob1 = registerAndConnect("late_bob")
        val alice = registerAndConnect("late_alice")
        becomeFriends(alice, bob1)
        sendAndVerify(alice, bob1, "before installation")

        val bob2 = newClient(deviceName = "Independent installation")
        bob2.loginAndProvision(bob1.username!!, TEST_PASSWORD)
        bob2.connect()
        assertTrue(bob2.getFriends().isEmpty())
        assertEquals(0L, bob2.inbox.depth())
        assertTrue(bob2.entries.all("directMessage").isEmpty())

        val oldDirectorySend = sendText(alice, bob1, "peer discovery is not proactive")
        assertEquals(listOf(bob1.deviceId), oldDirectorySend.recipients.map { it.deviceId })
        bob1.awaitContent("peer discovery is not proactive")
        assertFalse(bob2.deviceId in alice.devices.devicesOf(bob1.userId!!))

        val selfSync = sendText(bob1, alice, "old socket discovers own new device")
        assertEquals(listOf(bob2.deviceId), selfSync.selfSync.map { it.deviceId })
        bob2.awaitContent("old socket discovers own new device")
        alice.awaitContent("old socket discovers own new device")

        sendAndVerify(bob2, alice, "traffic from new installation")
        eventually { bob2.deviceId in alice.devices.devicesOf(bob1.userId!!) }
        val learned = sendText(alice, bob1, "now both peer devices")
        assertEquals(setOf(bob1.deviceId, bob2.deviceId), learned.recipients.map { it.deviceId }.toSet())
        assertTrue(learned.recipients.all { it.status == SubmissionStatus.ACCEPTED })
        bob1.awaitContent("now both peer devices")
        bob2.awaitContent("now both peer devices")
        assertTrue(bob2.getFriends().isEmpty(), "independent installation never imports a friend graph")
        bob1.disconnect(); bob2.disconnect(); alice.disconnect()
    }

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
