import XCTest
@testable import ObscuraKit

/// Tests for ephemeral, explicitly addressed typing signals.
final class SignalTests: XCTestCase {

    func testTypingSignalReceivedByExplicitRecipient() async throws {
        let alice = try await ObscuraTestClient.register()
        await rateLimitDelay()
        let bob = try await ObscuraTestClient.register()
        await rateLimitDelay()

        try await alice.connectWebSocket()
        try await bob.connectWebSocket()
        await rateLimitDelay()
        try await ObscuraTestClient.becomeFriends(alice, bob)

        let contextId = "opaque-thread"
        await alice.client.sendTyping(
            to: [try XCTUnwrap(bob.userId)],
            contextId: contextId,
            state: .started
        )

        let received = try await bob.waitForMessage(timeout: 10)
        XCTAssertEqual(received.type, "TYPING_SIGNAL")
        let active = await TypingStateRegistry.shared.tracker.activeUserIds(contextId: contextId)
        XCTAssertFalse(active.isEmpty)

        alice.disconnectWebSocket()
        bob.disconnectWebSocket()
    }

    func testStaleTypingSignalIsDropped() async {
        let tracker = TypingTracker()
        let now = UInt64(Date().timeIntervalSince1970 * 1_000)
        await tracker.receive(TypingEvent(
            contextId: "context",
            senderUserId: "alice",
            senderDeviceId: "device",
            timestamp: now - 10_000
        ))

        let active = await tracker.activeUserIds(contextId: "context")
        XCTAssertTrue(active.isEmpty)
    }

    func testTypingSignalAutoExpires() async throws {
        let tracker = TypingTracker()
        await tracker.receive(TypingEvent(
            contextId: "context",
            senderUserId: "alice",
            senderDeviceId: "device",
            timestamp: UInt64(Date().timeIntervalSince1970 * 1_000)
        ))
        let activeBefore = await tracker.isActive(contextId: "context")
        XCTAssertTrue(activeBefore)

        try await Task.sleep(nanoseconds: 5_500_000_000)
        let activeAfter = await tracker.isActive(contextId: "context")
        XCTAssertFalse(activeAfter)
    }

    func testStoppedTypingRemovesImmediately() async {
        let tracker = TypingTracker()
        await tracker.receive(TypingEvent(
            contextId: "context",
            senderUserId: "alice",
            senderDeviceId: "device",
            timestamp: UInt64(Date().timeIntervalSince1970 * 1_000)
        ))
        await tracker.remove(contextId: "context", senderDeviceId: "device")
        let active = await tracker.isActive(contextId: "context")
        XCTAssertFalse(active)
    }
}
