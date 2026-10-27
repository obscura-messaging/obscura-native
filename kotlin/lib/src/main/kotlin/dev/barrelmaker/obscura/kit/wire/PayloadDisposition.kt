package dev.barrelmaker.obscura.kit.wire

import obscura.client.v1.Client.ClientMessage.PayloadCase

internal enum class PayloadDisposition {
    /** Written to the inbox before the ack. */
    INBOXED,

    /** Changes kit-owned state before the ack. */
    KIT_INTERNAL,

    /** In memory only; acked without persistence. */
    DROPPABLE,
}

/**
 * Unknown and unset arms are inboxed: leaving them unacked would let any sender fill the server's
 * per-device queue, which evicts oldest first.
 */
internal fun payloadDisposition(arm: PayloadCase): PayloadDisposition = when (arm) {
    PayloadCase.FRIEND_REQUEST, PayloadCase.FRIEND_ACCEPT, PayloadCase.FRIEND_LIST_SYNC -> PayloadDisposition.KIT_INTERNAL
    PayloadCase.TYPING_SIGNAL -> PayloadDisposition.DROPPABLE
    else -> PayloadDisposition.INBOXED
}
