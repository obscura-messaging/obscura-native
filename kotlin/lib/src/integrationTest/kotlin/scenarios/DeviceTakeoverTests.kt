package scenarios

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * Device takeover replaces the current device's key material while preserving
 * its server device record.
 * Uses only public API + shared helpers.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DeviceTakeoverTests {

    companion object {
        private var serverUp = false

        @BeforeAll @JvmStatic fun check() {
            serverUp = checkServer()
        }
    }

    private fun need() = assumeTrue(serverUp)

    @Test @Order(1)
    fun `Takeover replaces keys on server`() = runBlocking {
        need()
        val client = registerAndConnect("tko_single")

        val oldRegId = client.registrationId
        client.takeoverDevice()
        assertNotEquals(oldRegId, client.registrationId,
            "registrationId should change after takeover")

        // Server still lists 1 device (same device, new keys)
        val devices = client.api.listDevices()
        assertEquals(1, devices.length(), "Should still have 1 device after takeover")

        client.disconnect()
    }

    @Test @Order(2)
    fun `friends keep messaging in both directions after a takeover`() = runBlocking {
        need()
        val alice = registerAndConnect("tko_alice")
        val bob = registerAndConnect("tko_bob")
        becomeFriends(alice, bob)
        sendAndVerify(bob, alice, "before takeover")

        val oldRegId = alice.registrationId
        alice.takeoverDevice()
        assertNotEquals(oldRegId, alice.registrationId, "registrationId should change after takeover")
        // Takeover ends this device's connection on the server; the gateway reconnects by itself.

        // The takeover announces from the new identity. Bob accepts the changed key, as Signal does
        // for an unverified contact, and replaces his session with Alice's device.
        assertEquals("DEVICE_ANNOUNCE", bob.waitForType("DEVICE_ANNOUNCE").type)
        sendAndVerify(bob, alice, "to the new identity")
        sendAndVerify(alice, bob, "from the new identity")

        alice.disconnect(); bob.disconnect()
    }
}
