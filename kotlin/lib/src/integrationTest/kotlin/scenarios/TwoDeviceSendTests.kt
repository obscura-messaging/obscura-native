package scenarios

import dev.barrelmaker.obscura.kit.ObscuraClient
import dev.barrelmaker.obscura.kit.ObscuraConfig
import dev.barrelmaker.obscura.kit.AuthState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.signal.libsignal.protocol.SignalProtocolAddress

/**
 * Two-device fan-out invariant.
 *
 * Alice has two linked devices and Bob has one. Both Alice devices must decrypt
 * Bob's sends after initial friendship, device announcement, and sender
 * reconnect. Signal sessions are addressed as `<deviceUuid>.1`; two devices
 * must never collapse onto one session address.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TwoDeviceSendTests {

    companion object {
        private var serverUp = false
        private var alice1: ObscuraClient? = null   // Alice, device 1 (phone)
        private var alice2: ObscuraClient? = null   // Alice, device 2 (laptop)
        private var bob: ObscuraClient? = null      // Bob, single device
        private var aliceUsername: String? = null

        @BeforeAll @JvmStatic fun setup() {
            serverUp = checkServer()
            if (!serverUp) return

            runBlocking {
                val name = uniqueName("f1_alice")
                aliceUsername = name

                // 1. Alice, device 1.
                alice1 = ObscuraClient(ObscuraConfig(API, deviceName = "Alice Phone"))
                alice1!!.register(name, TEST_PASSWORD)
                assertEquals(AuthState.AUTHENTICATED, alice1!!.authState.value)
                alice1!!.connect()

                // 2. Alice, device 2 — linked and approved BEFORE any friendship exists.
                alice2 = provisionAndApprove(alice1!!, name, "Alice Laptop")
                assertNotEquals(alice1!!.deviceId, alice2!!.deviceId, "Alice's two devices must differ")
                alice1!!.connect()
                alice2!!.connect()

                // 3. Bob, single device. Friendship both ways. Bob's befriend() fetches Alice's
                //    prekey bundles once, which lists BOTH of her devices in Bob's directory.
                bob = registerAndConnect("f1_bob")
                becomeFriends(bob!!, alice1!!)

                // Drain the friend fan-out from every queue.
                delay(800)
                drain(alice1!!); drain(alice2!!); drain(bob!!)
            }
        }

        private suspend fun drain(c: ObscuraClient) {
            try { while (true) { c.waitForMessage(2_000) } } catch (_: Exception) {}
        }
    }

    private fun need() = assumeTrue(serverUp && alice2 != null)

    /**
     * What the sender believes about Alice's devices, and which Signal sessions it actually holds.
     *
     * Prints the device-UUID address used by
     * `Messenger.addressFor(deviceUuid)`.
     */
    private suspend fun senderState(tag: String) {
        val b = bob!!
        val devIds = b.devices.devicesOf(alice1!!.userId!!).map { it.deviceId }
        println("── $tag ──")
        println("  bob's directory lists ${devIds.size} device(s) for Alice:")
        for (d in devIds) {
            val label = when (d) {
                alice1!!.deviceId -> "device1"
                alice2!!.deviceId -> "device2"
                else -> "UNKNOWN "
            }
            val hasSession = b.signalStore.containsSession(SignalProtocolAddress(d, 1))
            println("    $label $d -> encrypts at address ($d, 1)  session=${if (hasSession) "present" else "ABSENT"}")
        }
    }

    /** Bob sends one message; BOTH of Alice's devices must receive and decrypt it. */
    private suspend fun sendAndBothMustDecrypt(text: String) {
        // The app names the recipient by userId; the kit fans out to every device of that user.
        bob!!.send(
            recipientUserIds = listOf(alice1!!.userId!!),
            modelKey = "directMessage",
            entryId = "dm_${System.currentTimeMillis()}_${(0..99999).random()}",
            payload = org.json.JSONObject(mapOf(
                "conversationId" to listOf(bob!!.userId!!, alice1!!.userId!!).sorted().joinToString("_"),
                "content" to text,
            )).toString().toByteArray(),
        )

        val onD1 = runCatching { alice1!!.waitForMessage(15_000) }
        val onD2 = runCatching { alice2!!.waitForMessage(15_000) }
        // Content lives in the opaque APP_ENTRY payload.
        val d1ok = onD1.getOrNull()?.content() == text
        val d2ok = onD2.getOrNull()?.content() == text

        println("  RESULT '$text': device1 decrypted=$d1ok  device2 decrypted=$d2ok")
        if (!d1ok || !d2ok) {
            // Was an envelope delivered at all, and did it fail specifically at DECRYPT?
            println("  --- alice device1 debugLog (newest last) ---")
            alice1!!.debugLog.take(10).reversed().forEach { println("    $it") }
            println("  --- alice device2 debugLog (newest last) ---")
            alice2!!.debugLog.take(10).reversed().forEach { println("    $it") }
        }

        assertTrue(d1ok, "Alice device 1 must receive and decrypt '$text' — got ${onD1.getOrNull()?.content()}")
        assertTrue(d2ok, "Alice device 2 must receive and decrypt '$text' — got ${onD2.getOrNull()?.content()}")
    }

    @Test @Order(1)
    fun `server lists two devices for Alice`() = runBlocking {
        need()
        assertEquals(2, alice1!!.api.listDevices().length(), "Alice should have 2 devices on the server")
    }

    /**
     * Bob's initial prekey fetch lists both Alice devices. Reconnect must keep the same
     * per-device sessions.
     */
    @Test @Order(2)
    fun `two-device happy path - fresh befriend and after sender reconnect`() = runBlocking {
        need()

        // (a) Plain send, directory populated by befriend()'s prekey fetch.
        senderState("(a) fresh — directory from befriend()'s prekey fetch")
        sendAndBothMustDecrypt("multidevice-a-fresh")

        // (b) Bob reconnects; the persisted directory and sessions carry over.
        bob!!.disconnect(); delay(500); bob!!.connect(); delay(500)
        senderState("(b) after sender reconnect")
        sendAndBothMustDecrypt("multidevice-b-reconnect")

        alice1!!.disconnect(); alice2!!.disconnect(); bob!!.disconnect()
    }

    /** The real device-announcement path must survive a sender reconnect. */
    @Test @Order(3)
    fun `device announcement plus sender reconnect preserves two-device fan-out`() = runBlocking {
        need()
        val aliceId = alice1!!.userId!!

        // Reconnect the participants (Order 2 left them disconnected).
        alice1!!.connect(); alice2!!.connect(); bob!!.connect()
        delay(500)
        try { while (true) { bob!!.waitForMessage(1_500) } } catch (_: Exception) {}

        // announceDevices() broadcasts the own-device registry. Assert that
        // setup explicitly so the remainder cannot pass vacuously.
        val ownDevices = alice1!!.devices.devicesOf(aliceId)
        println("  alice1 own devices=${ownDevices.size} alice2 own devices=${alice2!!.devices.devicesOf(aliceId).size}")
        ownDevices.forEach { println("    own: ${it.deviceId} ${it.name}") }
        assertEquals(2, ownDevices.size,
            "Alice's own-device registry must list both devices before announcement")

        // Exercise the public propagation path, not a fabricated protobuf.
        alice1!!.announceDevices()

        // Bob must learn both devices before reconnect.
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline &&
            bob!!.devices.devicesOf(aliceId).size < 2) {
            try { bob!!.waitForMessage(1_500) } catch (_: Exception) {}
        }
        val storeDevices = bob!!.devices.devicesOf(aliceId)
        println("  after announceDevices(), bob's directory lists ${storeDevices.size} device(s) for Alice:")
        storeDevices.forEach { println("    ${it.deviceId} ${it.name}") }
        assertEquals(2, storeDevices.size,
            "Bob's directory must list both of Alice's devices after a real announceDevices() " +
                "Got: ${storeDevices.map { it.deviceId }}")
        assertTrue(
            storeDevices.map { it.deviceId }.toSet() == setOf(alice1!!.deviceId, alice2!!.deviceId),
            "Bob learned the wrong device UUIDs: ${storeDevices.map { it.deviceId }}"
        )

        // Reconnect keeps the populated directory. The addresses must remain distinct by device UUID.
        bob!!.disconnect(); delay(500); bob!!.connect(); delay(500)
        senderState("(c) directory populated by announce + sender reconnect")
        sendAndBothMustDecrypt("multidevice-c-announce")

        alice1!!.disconnect(); alice2!!.disconnect(); bob!!.disconnect()
    }
}
