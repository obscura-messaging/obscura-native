import XCTest
@testable import ObscuraKit

final class SignalReceiveAudienceTests: XCTestCase {
    private func message(
        contextId: String,
        state: Obscura_Client_V1_TypingState
    ) -> Obscura_Client_V1_ClientMessage {
        var signal = Obscura_Client_V1_TypingSignal()
        signal.contextID = contextId
        signal.state = state
        var message = Obscura_Client_V1_ClientMessage()
        message.typingSignal = signal
        message.timestamp = UInt64(Date().timeIntervalSince1970 * 1_000)
        return message
    }

    func testStartedStateAppearsUnderOpaqueContext() async throws {
        let client = try ObscuraClient(apiURL: "https://obscura.invalid")
        await TypingStateRegistry.shared.tracker.clearAll()

        await client.handleTypingSignal(
            message(contextId: "opaque-context", state: .started),
            sourceUserId: "alice",
            senderDeviceId: "alice-device"
        )

        let active = await TypingStateRegistry.shared.tracker
            .activeUserIds(contextId: "opaque-context")
        XCTAssertEqual(active, ["alice"])
    }

    func testAUserTypingOnTwoDevicesIsOneTyper() async throws {
        let client = try ObscuraClient(apiURL: "https://obscura.invalid")
        await TypingStateRegistry.shared.tracker.clearAll()

        for device in ["alice-phone", "alice-laptop"] {
            await client.handleTypingSignal(message(contextId: "context", state: .started),
                                            sourceUserId: "alice", senderDeviceId: device)
        }

        let active = await TypingStateRegistry.shared.tracker.activeUserIds(contextId: "context")
        XCTAssertEqual(active, ["alice"])
    }

    func testStoppedStateClearsOnlyTheSenderDevice() async throws {
        let client = try ObscuraClient(apiURL: "https://obscura.invalid")
        let tracker = TypingStateRegistry.shared.tracker
        await tracker.clearAll()
        await tracker.receive(TypingEvent(
            contextId: "context",
            senderUserId: "bob",
            senderDeviceId: "bob-device",
            timestamp: UInt64(Date().timeIntervalSince1970 * 1_000)
        ))

        await client.handleTypingSignal(
            message(contextId: "context", state: .started),
            sourceUserId: "alice",
            senderDeviceId: "alice-device"
        )
        await client.handleTypingSignal(
            message(contextId: "context", state: .stopped),
            sourceUserId: "alice",
            senderDeviceId: "alice-device"
        )

        let active = await tracker.activeUserIds(contextId: "context")
        XCTAssertEqual(active, ["bob"])
    }

    func testUnspecifiedStateIsIgnored() async throws {
        let client = try ObscuraClient(apiURL: "https://obscura.invalid")
        let tracker = TypingStateRegistry.shared.tracker
        await tracker.clearAll()

        await client.handleTypingSignal(
            message(contextId: "context", state: .unspecified),
            sourceUserId: "alice",
            senderDeviceId: "alice-device"
        )

        let active = await tracker.activeUserIds(contextId: "context")
        XCTAssertTrue(active.isEmpty)
    }
}
