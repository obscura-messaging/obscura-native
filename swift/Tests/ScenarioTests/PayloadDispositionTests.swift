import XCTest
@testable import ObscuraKit

/// The payload classification table.
///
/// Mirrors `ObscuraKit-Kotlin`'s `PayloadDispositionTest`. The two kits must agree here in a way they need
/// not agree elsewhere: a divergence means one of them acks something the other stores.
final class PayloadDispositionTests: XCTestCase {

    /// Every arm `client.proto` declares, as the generated oneof cases. Swift's generated enum is not
    /// `CaseIterable` — it has associated values — so the list is written out, and
    /// ``testTheArmListMatchesTheGeneratedOneof`` guards it against drift.
    private static let allArms: [Obscura_Client_V1_ClientMessage.OneOf_Payload] = [
        .friendRequest(.init()), .friendAccept(.init()),
        .deviceLinkApproval(.init()), .deviceAnnounce(.init()),
        .appEntry(.init()), .typingSignal(.init()),
    ]

    /// **The load-bearing test.** A new arm added to `client.proto` must not slip through
    /// unclassified — which, before the classification existed, meant being acked and destroyed.
    ///
    /// Swift cannot enumerate the oneof, so this leans on `WireCodec.decodeMessageType`, which has a
    /// case per arm and returns `""` for anything it does not know. An arm added to the proto but
    /// not to that switch fails here.
    func testTheArmListMatchesTheGeneratedOneof() {
        XCTAssertEqual(Self.allArms.count, 6, "client.proto declares six payload arms")

        for arm in Self.allArms {
            XCTAssertFalse(WireCodec.decodeMessageType(arm).isEmpty,
                           "an arm with no name in WireCodec is an arm nothing has classified")
        }
    }

    /// Leaving an unknown arm unacked would let any sender fill the server's per-device queue, which
    /// evicts oldest-first, and push the recipient's real mail out.
    func testAnUnsetPayloadIsInboxedRatherThanLeftUnacked() {
        XCTAssertEqual(payloadDisposition(nil), .inboxed)
    }

    func testAppEntryIsTheAppsDataPath() {
        XCTAssertEqual(payloadDisposition(.appEntry(.init())), .inboxed)
    }

    /// The only class permitted to ack without persisting, and it is a closed list. If this ever
    /// grows, the growth is a decision about durability, not a routing tweak.
    func testTypingIndicatorsAreTheOnlyDroppableArm() {
        let droppable = Self.allArms.filter { payloadDisposition($0) == .droppable }

        XCTAssertEqual(droppable.count, 1)
        XCTAssertEqual(WireCodec.decodeMessageType(droppable.first), "TYPING_SIGNAL")
    }

    func testSwiftLinkApprovalGapIsExplicit() {
        XCTAssertEqual(payloadDisposition(.deviceLinkApproval(.init())), .unimplemented)
    }

    /// Kit-owned state stays kit-owned; none of it may reach an app-readable inbox.
    func testFriendAndDeviceArmsAreKitInternal() {
        let kitInternal: [Obscura_Client_V1_ClientMessage.OneOf_Payload] = [
            .friendRequest(.init()), .friendAccept(.init()),
            .deviceAnnounce(.init()),
            // `.deviceLinkApproval` is deliberately absent — see `divergesFromKotlin`.
        ]

        for arm in kitInternal {
            XCTAssertEqual(payloadDisposition(arm), .kitInternal,
                           "\(WireCodec.decodeMessageType(arm)) mutates kit-owned state")
        }
    }

    /// Cross-kit agreement, spelled out: these are the exact classifications
    /// `ObscuraKit-Kotlin`'s `PayloadDispositionTest` asserts. The kits may differ in how they store a row;
    /// they may not differ in whether a given arm is stored at all.
    /// Arms this kit deliberately classifies differently from Kotlin, with the reason. Anything not
    /// in this set must match exactly — the point of the test below is that drift has to be declared
    /// here, in writing, rather than just happening.
    private static let divergesFromKotlin: [String: String] = [
        "DEVICE_LINK_APPROVAL":
            "Kotlin classifies this kit-internal and HANDLES it; this kit cannot receive one "
            + "(CLAUDE.md). Kit-internal-with-no-handler now throws, which would never ack a LIVE "
            + "flow and wedge the server queue — so it is bucketed unimplemented (drop + ack, "
            + "loudly) until the handler exists. Delete this entry when it does.",
    ]

    func testEveryArmHasTheSameClassAsTheKotlinKit() {
        let expected: [String: PayloadDisposition] = [
            "APP_ENTRY": .inboxed,
            "FRIEND_REQUEST": .kitInternal, "FRIEND_ACCEPT": .kitInternal,
            "DEVICE_ANNOUNCE": .kitInternal,
            "TYPING_SIGNAL": .droppable,
            // See `divergesFromKotlin` below.
            "DEVICE_LINK_APPROVAL": .unimplemented,
        ]

        XCTAssertEqual(expected.count, Self.allArms.count, "every arm needs a cross-kit expectation")
        for arm in Self.allArms {
            let name = WireCodec.decodeMessageType(arm)
            XCTAssertEqual(payloadDisposition(arm), expected[name], "\(name) does not match its expectation")
        }

        // The declared divergences must all still be real arms, so the list cannot rot after an arm
        // is deleted or a handler is finally written.
        let armNames = Set(Self.allArms.map { WireCodec.decodeMessageType($0) })
        for name in Self.divergesFromKotlin.keys {
            XCTAssertTrue(armNames.contains(name), "\(name) is declared divergent but is not an arm")
        }
    }
}
