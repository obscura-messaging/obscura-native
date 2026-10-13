import Foundation

/// Wire <-> app-facing mappings, pinned by `protocol/conformance/wire.json`.
enum WireCodec {
    /// The set payload arm's field name in upper snake case; "" when unset or unknown.
    static func messageKind(_ payload: Obscura_Client_V1_ClientMessage.OneOf_Payload?) -> String {
        switch payload {
        case .friendRequest?: return "FRIEND_REQUEST"
        case .friendAccept?: return "FRIEND_ACCEPT"
        case .appEntry?: return "APP_ENTRY"
        case .typingSignal?: return "TYPING_SIGNAL"
        case nil: return ""
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
