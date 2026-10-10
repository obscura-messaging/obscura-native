import Foundation

/// Single source of truth for the wire <-> app-facing-form mappings.
///
/// The message kind is the `ClientMessage.payload` oneof arm. A kit that maps these inconsistently
/// silently breaks cross-platform interop, so the mappings are consolidated here
/// and pinned by `protocol/conformance/wire.json`.
/// Mirrors the Kotlin kit's `WireCodec`.
///
/// Internal on purpose: SwiftProtobuf generates the `Obscura_Client_V1_*` types with
/// `internal` visibility, so this codec (which references them) is internal too.
/// Tests reach it via `@testable import ObscuraKit`.
enum WireCodec {

    // MARK: ClientMessage.payload oneof -> app string

    /// App-facing message-kind string: the set `payload` arm's field name,
    /// upper-snake. An unset payload (or `.none`) maps to "".
    static func decodeMessageType(_ payload: Obscura_Client_V1_ClientMessage.OneOf_Payload?) -> String {
        switch payload {
        case .friendRequest?: return "FRIEND_REQUEST"
        case .friendAccept?: return "FRIEND_ACCEPT"
        case .deviceLinkApproval?: return "DEVICE_LINK_APPROVAL"
        case .deviceAnnounce?: return "DEVICE_ANNOUNCE"
        case .appEntry?: return "APP_ENTRY"
        case .typingSignal?: return "TYPING_SIGNAL"
        case .none: return ""
        }
    }

    static func decodeTypingState(_ state: Obscura_Client_V1_TypingState) -> TypingState? {
        switch state {
        case .started: return .started
        case .stopped: return .stopped
        case .unspecified, .UNRECOGNIZED: return nil
        }
    }

    static func encodeTypingState(_ state: TypingState) -> Obscura_Client_V1_TypingState {
        switch state {
        case .started: return .started
        case .stopped: return .stopped
        }
    }
}
