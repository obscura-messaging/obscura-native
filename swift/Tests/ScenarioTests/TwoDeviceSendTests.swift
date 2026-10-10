import XCTest
@testable import ObscuraKit

/// Two-device Signal fan-out and session-addressing invariants.
///
/// Both kits address Signal sessions by device UUID.
/// These live-server tests exercise fan-out before and after a gateway
/// reconnect; they do not reconstruct a client or prove cold-start persistence.
final class TwoDeviceSendTests: XCTestCase {

    /// The server genuinely has two devices for Alice — if this fails, nothing below means anything.
    func testServerListsTwoDevicesForAlice() async throws {
        let alice1 = try await ObscuraTestClient.register()
        await rateLimitDelay()
        let alice2 = try await ObscuraTestClient.loginAndProvision(alice1.username, deviceName: "Alice Laptop")
        await rateLimitDelay()

        XCTAssertNotEqual(alice1.deviceId, alice2.deviceId, "Alice's two devices must have distinct UUIDs")
        let devices = try await alice1.api.listDevices()
        XCTAssertEqual(devices.count, 2, "Alice should have 2 devices on the server")
    }

    /// `announceDevices()` broadcasts the own-device registry, so it must not
    /// be empty.
    func testOwnDeviceRegistryIsPopulated() async throws {
        let alice1 = try await ObscuraTestClient.register()
        await rateLimitDelay()
        let alice2 = try await ObscuraTestClient.loginAndProvision(alice1.username, deviceName: "Alice Laptop")
        await rateLimitDelay()

        let own1 = await alice1.devices.getOwnDevices()
        let own2 = await alice2.devices.getOwnDevices()
        print("  alice1.getOwnDevices()=\(own1.count) alice2.getOwnDevices()=\(own2.count)")
        own1.forEach { print("    own(device1 view): \($0.deviceId) \($0.deviceName)") }
        own2.forEach { print("    own(device2 view): \($0.deviceId) \($0.deviceName)") }

        XCTAssertFalse(own1.isEmpty,
            "device 1's own-device registry is empty, so announceDevices() would broadcast nothing")
        XCTAssertFalse(own2.isEmpty,
            "device 2's own-device registry is empty, so announceDevices() would broadcast nothing")
        XCTAssertTrue(own1.contains { $0.deviceId == alice1.deviceId },
            "Device 1 must record itself in the own-device registry (register path)")
        XCTAssertTrue(own2.contains { $0.deviceId == alice2.deviceId },
            "Device 2 must record itself in the own-device registry (loginAndProvision path)")
    }

    /// After a real `validateAndApproveLink`, the **approver's**
    /// registry must list both devices — that list is what `approveLink` ships and what
    /// `announceDevices()` later broadcasts, so if it holds one device a friend can only ever learn
    /// one of them.
    ///
    /// **Known divergence:** Swift has no `DEVICE_LINK_APPROVAL` receive case,
    /// so the approvee does not import the friend export or complete own-device
    /// list. This test asserts only the supported
    /// approver side.
    func testLinkApprovalPopulatesTheApproverRegistry() async throws {
        let alice1 = try await ObscuraTestClient.register()
        await rateLimitDelay()
        let alice2 = try await ObscuraTestClient.loginAndProvision(alice1.username, deviceName: "Alice Laptop")
        await rateLimitDelay()

        try await alice2.connectWebSocket()
        await rateLimitDelay()

        let code = try XCTUnwrap(alice2.client.generateLinkCode(),
            "the pending device must be able to generate a link code")
        try await alice1.client.validateAndApproveLink(code)
        await rateLimitDelay()

        let approver = await alice1.devices.getOwnDevices()
        print("  approver registry after validateAndApproveLink: \(approver.map { $0.deviceId })")
        XCTAssertEqual(approver.count, 2,
            "the approver must record BOTH devices — this list is what approveLink ships and what "
            + "announceDevices() broadcasts")
        XCTAssertTrue(approver.contains { $0.deviceId == alice1.deviceId },
            "approver registry must contain the approving device")
        XCTAssertTrue(approver.contains { $0.deviceId == alice2.deviceId },
            "approver registry must contain the newly-approved device")

        alice2.disconnectWebSocket()
    }

    /// Alice has two devices; Bob befriends her, reconnects, then sends. Both
    /// devices must receive and decrypt.
    func testBothDevicesDecryptAfterSenderReconnect() async throws {
        let alice1 = try await ObscuraTestClient.register()
        await rateLimitDelay()
        let alice2 = try await ObscuraTestClient.loginAndProvision(alice1.username, deviceName: "Alice Laptop")
        await rateLimitDelay()
        let bob = try await ObscuraTestClient.register()
        await rateLimitDelay()

        try await alice1.connectWebSocket()
        try await alice2.connectWebSocket()
        try await bob.connectWebSocket()
        await rateLimitDelay()

        // Friendship both ways. Bob's befriend() fetches Alice's prekey bundles, enumerating both
        // of her devices into his device map.
        try await bob.befriend(alice1.userId!, username: alice1.username)
        _ = try await alice1.waitForMessage(timeout: 15)   // FRIEND_REQUEST on device 1
        _ = try? await alice2.waitForMessage(timeout: 10)  // ...and on device 2 (fan-out)
        try await alice1.acceptFriend(bob.userId!)
        _ = try await bob.waitForMessage(timeout: 15)      // FRIEND_ACCEPT

        // (a) Fresh send with the device map from the prekey fetch.
        try await bob.client.send(
            to: [alice1.userId!], modelKey: "testModel", entryId: "swift-2dev-a-fresh",
            payload: Data("fresh".utf8)
        )
        let a1 = try await alice1.waitForMessage(timeout: 15)
        let a2 = try await alice2.waitForMessage(timeout: 15)
        XCTAssertEqual(a1.type, "APP_ENTRY", "Alice device 1 must decrypt the fresh send")
        XCTAssertEqual(a2.type, "APP_ENTRY", "Alice device 2 must decrypt the fresh send")

        // (b) Reconnect the gateway, then send again. send() refreshes prekey bundles,
        // so this does not exercise a reconstructed client's persisted device map.
        bob.disconnectWebSocket()
        try await Task.sleep(nanoseconds: 500_000_000)
        try await bob.connectWebSocket()
        await rateLimitDelay()

        try await bob.client.send(
            to: [alice1.userId!], modelKey: "testModel", entryId: "swift-2dev-b-reconnect",
            payload: Data("reconnected".utf8)
        )
        let b1 = try await alice1.waitForMessage(timeout: 15)
        let b2 = try await alice2.waitForMessage(timeout: 15)
        XCTAssertEqual(b1.type, "APP_ENTRY",
            "Alice device 1 must decrypt after the sender reconnected")
        XCTAssertEqual(b2.type, "APP_ENTRY",
            "Alice device 2 must decrypt after the sender reconnected")

        let stored1 = try await alice1.client.inbox.peek()
        let stored2 = try await alice2.client.inbox.peek()
        XCTAssertTrue(stored1.contains { $0.entryId == "swift-2dev-b-reconnect" },
            "Device 1 must have persisted the message it acked")
        XCTAssertTrue(stored2.contains { $0.entryId == "swift-2dev-b-reconnect" },
            "Device 2 must have persisted the message it acked")

        alice1.disconnectWebSocket()
        alice2.disconnectWebSocket()
        bob.disconnectWebSocket()
    }
}
