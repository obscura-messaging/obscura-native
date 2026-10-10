package dev.barrelmaker.obscura.kit.wire

import obscura.client.v1.Client.ClientMessage.PayloadCase
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * The payload classification table. The exhaustiveness test matters most: a new arm added to
 * `client.proto` must not slip through unclassified.
 */
class PayloadDispositionTest {

    /**
     * The expected class of every arm, written out. Also the cross-kit contract: `ObscuraKit-swift`'s
     * `PayloadDispositionTests.testEveryArmHasTheSameClassAsTheKotlinKit` asserts the same table. The kits
     * may differ in how they store a row; they may not differ in whether an arm is stored at all.
     */
    private val expected = mapOf(
        PayloadCase.APP_ENTRY to PayloadDisposition.INBOXED,
        PayloadCase.FRIEND_REQUEST to PayloadDisposition.KIT_INTERNAL,
        PayloadCase.FRIEND_ACCEPT to PayloadDisposition.KIT_INTERNAL,
        PayloadCase.DEVICE_ANNOUNCE to PayloadDisposition.KIT_INTERNAL,
        PayloadCase.DEVICE_LINK_APPROVAL to PayloadDisposition.KIT_INTERNAL,
        PayloadCase.TYPING_SIGNAL to PayloadDisposition.DROPPABLE,
    )

    /**
     * A new arm in `client.proto` must fail here rather than be discovered
     * later as a message that quietly vanished.
     *
     * Kotlin has no exhaustive-switch backstop because `classify` needs its
     * fallback. This explicit table requires a deliberate policy for every arm.
     */
    @Test
    fun `every payload arm in the proto has an expected class, and every expectation is a real arm`() {
        val armsInProto = PayloadCase.entries.filter { it != PayloadCase.PAYLOAD_NOT_SET }.toSet()

        val missing = armsInProto - expected.keys
        assertTrue(missing.isEmpty(),
            "new arm(s) in client.proto with no decision about what they may do: $missing")

        val stale = expected.keys - armsInProto
        assertTrue(stale.isEmpty(), "expectation(s) naming an arm that no longer exists: $stale")

        armsInProto.forEach { arm ->
            assertEquals(expected[arm], payloadDisposition(arm), "$arm is classified differently than expected")
        }
    }

    /**
     * Leaving an unknown arm unacked would let any sender fill the server's per-device queue, which
     * evicts oldest-first, and push the recipient's real mail out.
     */
    @Test
    fun `an unknown arm is inboxed rather than left unacked`() {
        assertEquals(PayloadDisposition.INBOXED, payloadDisposition(PayloadCase.PAYLOAD_NOT_SET))
    }

    @Test
    fun `model sync is the app's data path`() {
        assertEquals(PayloadDisposition.INBOXED, payloadDisposition(PayloadCase.APP_ENTRY))
    }

    /**
     * The only class permitted to ack without persisting, and it is a closed list. If this ever
     * grows, the growth is a decision about durability, not a routing tweak.
     */
    @Test
    fun `typing indicators are the only droppable arm`() {
        val droppable = PayloadCase.entries.filter { payloadDisposition(it) == PayloadDisposition.DROPPABLE }

        assertEquals(listOf(PayloadCase.TYPING_SIGNAL), droppable)
    }

    /** Kit-owned state stays kit-owned; none of it may reach an app-readable inbox. */
    @Test
    fun `friend and device arms are kit-internal`() {
        listOf(
            PayloadCase.FRIEND_REQUEST, PayloadCase.FRIEND_ACCEPT,
            PayloadCase.DEVICE_ANNOUNCE, PayloadCase.DEVICE_LINK_APPROVAL,
        ).forEach {
            assertEquals(PayloadDisposition.KIT_INTERNAL, payloadDisposition(it), "$it mutates kit-owned state")
        }
    }
}
