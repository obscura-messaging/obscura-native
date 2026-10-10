package scenarios

import dev.barrelmaker.obscura.kit.ObscuraClient
import dev.barrelmaker.obscura.kit.ObscuraConfig
import dev.barrelmaker.obscura.kit.AuthState
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * Device link flow: provision second device, approve via link code,
 * verify server shows both devices, messages fan out.
 *
 * Device approval is enforced — loginAndProvision puts the device in
 * PENDING_APPROVAL state until an existing device approves via link code.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DeviceLinkFlowTests {

    companion object {
        private var serverUp = false

        @BeforeAll @JvmStatic fun check() {
            serverUp = checkServer()
        }
    }

    private fun need() = assumeTrue(serverUp)

    @Test @Order(1)
    fun `loginAndProvision puts device in PENDING_APPROVAL`() = runBlocking {
        need()

        val username = uniqueName("dlf_pend")
        val device1 = ObscuraClient(ObscuraConfig(API, deviceName = "Device 1"))
        device1.register(username, TEST_PASSWORD)
        assertEquals(AuthState.AUTHENTICATED, device1.authState.value)

        val device2 = ObscuraClient(ObscuraConfig(API, deviceName = "Device 2"))
        device2.loginAndProvision(username, TEST_PASSWORD, "Device 2")
        assertEquals(AuthState.PENDING_APPROVAL, device2.authState.value,
            "New device must be PENDING_APPROVAL until existing device approves")
        assertNotNull(device2.deviceId)
        assertNotEquals(device1.deviceId, device2.deviceId)

        // Server shows both devices even before approval (device is provisioned, just not approved)
        val devices = device1.api.listDevices()
        assertEquals(2, devices.length(), "Server should show 2 devices")
    }

    @Test @Order(2)
    fun `Full link code approval flow — PENDING to AUTHENTICATED`() = runBlocking {
        need()

        val username = uniqueName("dlf_approve")
        val device1 = ObscuraClient(ObscuraConfig(API, deviceName = "Device 1"))
        device1.register(username, TEST_PASSWORD)
        device1.connect()

        // Provision + approve via helper
        val device2 = provisionAndApprove(device1, username, "Device 2")
        assertEquals(AuthState.AUTHENTICATED, device2.authState.value)

        device1.disconnect()
        device2.disconnect()
    }

    @Test @Order(3)
    fun `Approved device can receive messages from friends`() = runBlocking {
        need()

        val username = uniqueName("dlf_recv")
        val device1 = ObscuraClient(ObscuraConfig(API, deviceName = "Device 1"))
        device1.register(username, TEST_PASSWORD)
        device1.connect()

        val device2 = provisionAndApprove(device1, username, "Device 2")

        val carol = registerAndConnect("dlf_carol")
        becomeFriends(device1, carol)

        // Drain any sync messages on device2
        try { while (true) { device2.waitForMessage(2_000) } } catch (_: Exception) {}

        // Carol sends — both devices should receive
        sendAndVerify(carol, device1, "Hello both devices")

        val msg1 = device1.waitForType("APP_ENTRY")
        assertEquals("Hello both devices", msg1.content())

        val msg2 = device2.waitForType("APP_ENTRY")
        assertEquals("Hello both devices", msg2.content())

        device1.disconnect(); device2.disconnect(); carol.disconnect()
    }

    @Test @Order(4)
    fun `loginAndProvision authenticates a sole device`() = runBlocking {
        need()

        val username = uniqueName("dlf_sole")
        ObscuraClient(ObscuraConfig(API)).api.registerUser(username, TEST_PASSWORD)

        val device = ObscuraClient(ObscuraConfig(API, deviceName = "Only Device"))
        device.loginAndProvision(username, TEST_PASSWORD, "Only Device")
        assertEquals(AuthState.AUTHENTICATED, device.authState.value,
            "With no other device to approve it, the new device must be AUTHENTICATED")
    }
}
