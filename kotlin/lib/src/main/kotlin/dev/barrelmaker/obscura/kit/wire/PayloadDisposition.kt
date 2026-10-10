package dev.barrelmaker.obscura.kit.wire

import obscura.client.v1.Client.ClientMessage.PayloadCase

/**
 * What a payload arm is allowed to do on receipt. Every arm is classified so persist-then-ack has
 * a defined meaning for each one.
 */
internal enum class PayloadDisposition {
    /** Application content. Goes in the inbox; the app drains it. Ack only after the row commits. */
    INBOXED,

    /** Mutates kit-owned state (friend graph or devices). Ack only after the kit's write. */
    KIT_INTERNAL,

    /** Ephemeral by design, no durable delivery guarantee. MAY be acked without persistence. */
    DROPPABLE,
}

/**
 * An unknown arm is inboxed unparsed. Leaving it unacked would let any sender fill the server's
 * per-device queue, which evicts oldest-first, and push real mail out. Refusing to ack is reserved
 * for transient local failures.
 */
internal fun payloadDisposition(arm: PayloadCase): PayloadDisposition = when (arm) {
    // The app's entire data path.
    PayloadCase.APP_ENTRY -> PayloadDisposition.INBOXED

    // Kit-owned state, all with live handlers in ObscuraClient.routeMessage.
    PayloadCase.FRIEND_REQUEST,
    PayloadCase.FRIEND_ACCEPT,
    PayloadCase.DEVICE_ANNOUNCE,
    PayloadCase.DEVICE_LINK_APPROVAL -> PayloadDisposition.KIT_INTERNAL

    // Typing indicators are in-memory only: the one class acked without persistence.
    PayloadCase.TYPING_SIGNAL -> PayloadDisposition.DROPPABLE

    // Unknown or future arm, and PAYLOAD_NOT_SET. Inbox it unparsed rather than destroy it.
    else -> PayloadDisposition.INBOXED
}
