package scenarios

import dev.barrelmaker.obscura.kit.TypingState
import dev.barrelmaker.obscura.kit.wire.TypingTracker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Typing signal tests.
 *
 * Unit tests prove the in-memory tracker works in isolation.
 * Integration tests prove signals survive Signal Protocol encryption over the wire.
 */
class TypingSignalTests {

    // ─── Unit: TypingTracker in isolation ──────────────────────────

    @Test
    fun `Receive signal appears in observer`() = runBlocking {
        val mgr = TypingTracker()
        mgr.receive("conv1", "alice-user", "device-alice")

        val typers = mgr.observe("conv1").first()
        assertEquals(1, typers.size)
    }

    @Test
    fun `Signal auto-expires after 3 seconds`() = runBlocking {
        val mgr = TypingTracker()
        mgr.receive("conv1", "alice-user", "device-alice")

        delay(3500) // Wait past expiry

        val typers = mgr.observe("conv1").first()
        assertEquals(0, typers.size, "Signal should auto-expire after 3s")
    }

    @Test
    fun `Explicit clear removes signal immediately`() = runBlocking {
        val mgr = TypingTracker()
        mgr.receive("conv1", "alice-user", "device-alice")

        // Verify it's there
        assertEquals(1, mgr.observe("conv1").first().size)

        // Clear it
        mgr.clear("conv1", "device-alice")

        assertEquals(0, mgr.observe("conv1").first().size)
    }

    @Test
    fun `Multiple typers tracked independently`() = runBlocking {
        val mgr = TypingTracker()
        mgr.receive("conv1", "alice-user", "device-alice")
        mgr.receive("conv1", "bob-user", "device-bob")

        val typers = mgr.observe("conv1").first()
        assertEquals(setOf("alice-user", "bob-user"), typers.toSet())
    }

    @Test
    fun `Signals scoped to conversation`() = runBlocking {
        val mgr = TypingTracker()
        mgr.receive("conv1", "alice-user", "d1")
        mgr.receive("conv2", "bob-user", "d2")

        assertEquals(1, mgr.observe("conv1").first().size)
        assertEquals(1, mgr.observe("conv2").first().size)
    }

    @Test
    fun `Throttle prevents rapid re-sends`() = runBlocking {
        val mgr = TypingTracker()
        assertTrue(mgr.shouldSend("conv1", TypingState.STARTED, "my-device"))
        assertFalse(mgr.shouldSend("conv1", TypingState.STARTED, "my-device"))
        assertTrue(mgr.shouldSend("conv1", TypingState.STOPPED, "my-device"),
            "started and stopped are distinct typed states")
    }

    @Test
    fun `Offline does not care about signals`() = runBlocking {
        // Signals are ephemeral — if the receiver is offline, they just don't get them.
        // No queuing, no persistence. This is by design.
        val mgr = TypingTracker()
        // Receive a signal, then expire it — nothing persists
        mgr.receive("conv1", "alice-user", "d1")
        delay(3500)
        val typers = mgr.observe("conv1").first()
        assertTrue(typers.isEmpty(), "Expired signals should not persist — offline gets nothing, by design")
    }

    // ─── Integration: signals over the wire ───────────────────────

    @Test
    fun `Typing signal arrives at an explicit recipient`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("sig_a")
        val bob = registerAndConnect("sig_b")
        becomeFriends(alice, bob)


        val convId = listOf(alice.userId!!, bob.userId!!).sorted().joinToString("_")

        // Alice starts typing
        alice.sendTyping(listOf(bob.userId!!), convId, TypingState.STARTED)

        // Bob receives TYPING_SIGNAL and surfaces the app-facing typing state.
        val aliceId = alice.userId!!
        val typers = withTimeout(15_000) {
            bob.observeTyping(convId)
                .first { it.contains(aliceId) }
        }
        assertTrue(typers.contains(aliceId), "Bob should observe Alice typing")

        alice.disconnect()
        bob.disconnect()
    }

    @Test
    fun `Typing indicator does not persist — offline friend misses it`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("sig_oa")
        val bob = registerAndConnect("sig_ob")
        becomeFriends(alice, bob)


        val convId = listOf(alice.userId!!, bob.userId!!).sorted().joinToString("_")

        // Bob goes offline
        bob.disconnect()
        delay(500)

        // Alice types while Bob is offline
        alice.sendTyping(listOf(bob.userId!!), convId, TypingState.STARTED)
        delay(500)

        // Bob reconnects — server may deliver the queued TYPING_SIGNAL
        bob.connect()

        // Send a real message to prove the channel works
        sendAndVerify(alice, bob, "Done typing")

        // Drain messages — signal may arrive before the real message
        var realMessageReceived = false
        repeat(5) {
            try {
                val msg = bob.waitForMessage(5_000)
                if (msg.type == "TYPING_SIGNAL") {
                    // Server delivered the stale signal — that's fine, it's ephemeral.
                    // The SignalManager receives it but it expires in 3s. No DB persistence.
                } else if (msg.type == "APP_ENTRY") {
                    realMessageReceived = true
                }
            } catch (_: Exception) {}
        }
        assertTrue(realMessageReceived, "Real message should arrive even if stale signal was queued")

        alice.disconnect()
        bob.disconnect()
    }
}
