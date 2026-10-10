import Foundation

/// What a payload arm is allowed to do on receipt. Every arm is classified so persist-then-ack has
/// a defined meaning for each one.
enum PayloadDisposition: Equatable {
    /// Application content. Goes in the inbox; the app drains it. Ack only after the row commits.
    case inboxed

    /// Mutates kit-owned state (friend graph or devices). Ack only after the kit's write.
    case kitInternal

    /// Ephemeral by design, no durable delivery guarantee. MAY be acked without persistence.
    case droppable

    /// Declared arms with no receive contract. Diagnose, drop, and ack them so an unsupported arm
    /// cannot wedge the queue. Unknown future arms remain distinct and are inboxed.
    case unimplemented
}

/// An unknown arm is inboxed unparsed. Leaving it unacked would let any sender fill the server's
/// per-device queue, which evicts oldest-first, and push real mail out. Refusing to ack is reserved
/// for transient local failures.
///
/// - Note: Swift's generated oneof has no `PAYLOAD_NOT_SET` case; an unset payload is `nil`, which
///   lands in the same `default` and is inboxed for the same reason.
func payloadDisposition(_ payload: Obscura_Client_V1_ClientMessage.OneOf_Payload?) -> PayloadDisposition {
    switch payload {
    // The app's entire data path.
    case .appEntry?:
        return .inboxed

    // Kit-owned state, all with live handlers in `ObscuraClient.routeMessage`.
    case .friendRequest?, .friendAccept?, .deviceAnnounce?:
        return .kitInternal

    // Swift sends this live arm but has no receive handler. Drop and acknowledge it loudly rather
    // than leaving a permanently unprocessable envelope on the server. Move it to kitInternal when
    // the receive handler lands.
    case .deviceLinkApproval?:
        return .unimplemented

    // Typing indicators are in-memory only: the one class acked without persistence.
    case .typingSignal?:
        return .droppable

    // Unknown or future arm, and an unset payload. Inbox it unparsed rather than destroy it.
    default:
        return .inboxed
    }
}
