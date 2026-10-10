import XCTest
@testable import ObscuraKit

/// Smoke tests for connection resilience — reconnect, ping keepalive, message survival.
final class ReconnectTests: XCTestCase {

    func testFailedConnectReturnsToDisconnected() async throws {
        let client = try ObscuraClient(apiURL: "http://127.0.0.1:1")

        do {
            try await client.connect()
            XCTFail("an unreachable server must fail the connection")
        } catch {
            XCTAssertEqual(client.connectionState, .disconnected)
        }
    }

    func testConnectIsSerializedAndIdempotent() async throws {
        let alice = try await ObscuraTestClient.register()
        await rateLimitDelay()

        async let first: Void = alice.client.connect()
        async let second: Void = alice.client.connect()
        _ = try await (first, second)

        let concurrentGeneration = await alice.client.gateway.connectionGeneration()
        XCTAssertEqual(concurrentGeneration, 1)

        try await alice.client.connect()
        let repeatedGeneration = await alice.client.gateway.connectionGeneration()
        XCTAssertEqual(repeatedGeneration, 1)

        alice.disconnectWebSocket()
    }

    /// Gateway disconnect triggers auto-reconnect. Entries flow after reconnect.
    func testAutoReconnect_messagesFlowAfterDrop() async throws {
        let alice = try await ObscuraTestClient.register()
        await rateLimitDelay()
        let bob = try await ObscuraTestClient.register()
        await rateLimitDelay()

        try await alice.connectWebSocket()
        try await bob.connectWebSocket()
        await rateLimitDelay()
        try await ObscuraTestClient.becomeFriends(alice, bob)

        try await alice.client.send(
            to: [bob.userId!], modelKey: "testModel", entryId: "before-drop",
            payload: Data("before drop".utf8)
        )
        let msg1 = try await bob.waitForMessage(timeout: 10)
        XCTAssertEqual(msg1.type, "APP_ENTRY")

        // Simulate gateway disconnect on Bob's side
        await bob.client.gateway.disconnect()
        await rateLimitDelay()

        // Bob's client should auto-reconnect
        // Wait for reconnect (backoff starts at 1s)
        try await Task.sleep(nanoseconds: 3_000_000_000)

        // Verify Bob is connected again
        XCTAssertEqual(bob.client.connectionState, .connected,
                       "Bob should auto-reconnect after gateway drop")

        try await alice.client.send(
            to: [bob.userId!], modelKey: "testModel", entryId: "after-reconnect",
            payload: Data("after reconnect".utf8)
        )
        let msg2 = try await bob.waitForMessage(timeout: 10)
        XCTAssertEqual(msg2.type, "APP_ENTRY")

        alice.disconnectWebSocket()
        bob.disconnectWebSocket()
    }

    /// Entries survive a reconnect cycle — the one sent while Bob was connected AND the one the
    /// server queued while he was not.
    ///
    /// The assertion is against the INBOX, not against the wake-up. A `waitForMessage` proves an
    /// envelope arrived; only the durable row proves it survived, and after an ack the row is the
    /// only copy there is. Both ids are checked by name rather than
    /// by counting, so "delivered twice" cannot pass as "both delivered".
    func testEntriesSurviveReconnect() async throws {
        let alice = try await ObscuraTestClient.register()
        await rateLimitDelay()
        let bob = try await ObscuraTestClient.register()
        await rateLimitDelay()

        try await alice.connectWebSocket()
        try await bob.connectWebSocket()
        await rateLimitDelay()
        try await ObscuraTestClient.becomeFriends(alice, bob)

        let beforeId = "story_before_\(UUID().uuidString)"
        let duringId = "story_during_\(UUID().uuidString)"

        // Sent while Bob is connected
        try await alice.client.send(
            to: [bob.userId!], modelKey: "story", entryId: beforeId,
            payload: try JSONSerialization.data(withJSONObject: ["content": "before drop"]))
        _ = try await bob.waitForMessage(timeout: 10)

        // Disconnect Bob
        await bob.client.gateway.disconnect()
        await rateLimitDelay()

        // Alice sends while Bob is reconnecting — the server queues this one
        try await alice.client.send(
            to: [bob.userId!], modelKey: "story", entryId: duringId,
            payload: try JSONSerialization.data(withJSONObject: ["content": "during reconnect"]))
        await rateLimitDelay()

        // Wait for Bob to reconnect
        try await Task.sleep(nanoseconds: 3_000_000_000)

        // Bob should get the queued story
        let msg = try await bob.waitForMessage(timeout: 10)
        XCTAssertEqual(msg.type, "APP_ENTRY")

        let stored = try await bob.client.inbox.peek(limit: 200).compactMap(\.entryId)
        XCTAssertTrue(stored.contains(beforeId),
                      "the entry sent before the drop must still be stored after the reconnect")
        XCTAssertTrue(stored.contains(duringId),
                      "the entry queued during the drop must arrive and be stored")

        alice.disconnectWebSocket()
        bob.disconnectWebSocket()
    }

    /// Explicit disconnect() does NOT auto-reconnect.
    func testExplicitDisconnect_noReconnect() async throws {
        let alice = try await ObscuraTestClient.register()
        await rateLimitDelay()

        try await alice.connectWebSocket()
        await rateLimitDelay()

        // Explicit disconnect
        alice.disconnectWebSocket()
        try await Task.sleep(nanoseconds: 3_000_000_000)

        // Should stay disconnected — no auto-reconnect
        XCTAssertEqual(alice.client.connectionState, .disconnected,
                       "Explicit disconnect should not trigger auto-reconnect")
    }

    /// Connection survives idle period (ping keepalive).
    func testPingKeepAlive_connectionSurvivesIdle() async throws {
        let alice = try await ObscuraTestClient.register()
        await rateLimitDelay()
        let bob = try await ObscuraTestClient.register()
        await rateLimitDelay()

        try await alice.connectWebSocket()
        try await bob.connectWebSocket()
        await rateLimitDelay()
        try await ObscuraTestClient.becomeFriends(alice, bob)

        // Idle for 35 seconds (longer than ping interval of 30s)
        try await Task.sleep(nanoseconds: 35_000_000_000)

        // Connection should still be alive
        XCTAssertEqual(alice.client.connectionState, .connected)
        XCTAssertEqual(bob.client.connectionState, .connected)

        try await alice.client.send(
            to: [bob.userId!], modelKey: "testModel", entryId: "still-alive",
            payload: Data("still alive".utf8)
        )
        let msg = try await bob.waitForMessage(timeout: 10)
        XCTAssertEqual(msg.type, "APP_ENTRY")

        alice.disconnectWebSocket()
        bob.disconnectWebSocket()
    }
}
