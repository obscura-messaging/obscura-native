package scenarios

import dev.barrelmaker.obscura.kit.TypingState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Typing delivery follows the explicit caller-supplied audience. */
class SignalAudienceTests {

    @Test
    fun `typing reaches a named peer and not an unnamed friend`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("sig_aud_a")
        val bob = registerAndConnect("sig_aud_b")
        val carol = registerAndConnect("sig_aud_c")
        becomeFriends(alice, bob)
        becomeFriends(alice, carol)

        val contextId = "opaque-thread"
        alice.sendTyping(listOf(bob.userId!!), contextId, TypingState.STARTED)

        val bobSees = withTimeout(15_000) {
            bob.observeTyping(contextId).first { it.contains(alice.userId!!) }
        }
        assertTrue(bobSees.contains(alice.userId!!))

        val carolSees = withTimeoutOrNull(3_000) {
            carol.observeTyping(contextId).first { it.isNotEmpty() }
        }
        assertNull(carolSees, "an unnamed recipient must not receive the signal")

        listOf(alice, bob, carol).forEach { it.disconnect() }
    }

    @Test
    fun `typing context is opaque rather than an audience encoding`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("sig_opaque_a")
        val bob = registerAndConnect("sig_opaque_b")
        becomeFriends(alice, bob)

        val contextId = "not-a-conversation-id"
        alice.sendTyping(listOf(bob.userId!!), contextId, TypingState.STARTED)

        val observed = withTimeout(15_000) {
            bob.observeTyping(contextId).first { it.contains(alice.userId!!) }
        }
        assertTrue(observed.isNotEmpty())

        listOf(alice, bob).forEach { it.disconnect() }
    }

    @Test
    fun `typing can name multiple recipients explicitly`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("sig_multi_a")
        val bob = registerAndConnect("sig_multi_b")
        val carol = registerAndConnect("sig_multi_c")
        becomeFriends(alice, bob)
        becomeFriends(alice, carol)

        val contextId = "shared-context"
        alice.sendTyping(
            listOf(bob.userId!!, carol.userId!!),
            contextId,
            TypingState.STARTED,
        )

        for (client in listOf(bob, carol)) {
            val observed = withTimeout(15_000) {
                client.observeTyping(contextId).first { it.contains(alice.userId!!) }
            }
            assertTrue(observed.isNotEmpty())
        }

        listOf(alice, bob, carol).forEach { it.disconnect() }
    }
}
