import XCTest
@testable import ObscuraKit

/// Matches Kotlin's DeviceTakeoverTests.kt
/// Device takeover: replace identity key, verify server accepts, messaging resumes.
final class DeviceTakeoverTests: XCTestCase {

    func testTakeoverReplacesKeysOnServer() async throws {
        let alice = try await ObscuraTestClient.register()
        let oldRegId = alice.client.registrationId
        await rateLimitDelay()

        try await alice.client.takeoverDevice()
        await rateLimitDelay()

        XCTAssertNotEqual(oldRegId, alice.client.registrationId, "Registration ID should change after takeover")

        // New identity should be persisted in Signal store
        XCTAssertNotNil(alice.client.persistentSignalStore)
        XCTAssertTrue(alice.client.persistentSignalStore!.hasPersistedIdentity)

        // Server still lists 1 device (same device, new keys)
        let devices = try await alice.api.listDevices()
        XCTAssertEqual(devices.count, 1)
    }

    func testCanMessageNewUserAfterTakeover() async throws {
        // Alice takes over, then befriends a NEW user (clean session)
        let alice = try await ObscuraTestClient.register()
        await rateLimitDelay()
        try await alice.client.takeoverDevice()
        await rateLimitDelay()

        let bob = try await ObscuraTestClient.register()
        await rateLimitDelay()

        try await bob.connectWebSocket()
        await rateLimitDelay()

        // Alice befriends Bob AFTER takeover — Bob fetches Alice's new keys
        try await alice.connectWebSocket()
        await rateLimitDelay()

        try await alice.befriend(bob.userId!, username: bob.username)
        let req = try await bob.waitForMessage(timeout: 15) // FRIEND_REQUEST
        XCTAssertEqual(req.type, "FRIEND_REQUEST")

        try await bob.acceptFriend(alice.userId!)
        _ = try await alice.waitForMessage(timeout: 15) // FRIEND_ACCEPT

        // Verify friendship established
        let aliceFriend = await alice.friends.getFriend(bob.userId!)
        XCTAssertEqual(aliceFriend?.status, .accepted)

        try await alice.client.send(
            to: [bob.userId!],
            modelKey: "testModel",
            entryId: "post-takeover",
            payload: Data("Post-takeover entry".utf8)
        )
        let msg = try await bob.waitForMessage(timeout: 15)
        XCTAssertEqual(msg.type, "APP_ENTRY")
        let rows = try await bob.client.inbox.peek()
        XCTAssertTrue(rows.contains { $0.entryId == "post-takeover" })

        alice.disconnectWebSocket()
        bob.disconnectWebSocket()
    }

    func testFriendsKeepMessagingInBothDirectionsAfterATakeover() async throws {
        let (alice, bob) = try await ObscuraTestClient.registerPairAndBecomeFriends()
        try await assertDelivers(from: bob, to: alice, "before-takeover")

        let oldRegId = alice.client.registrationId
        try await alice.client.takeoverDevice()
        XCTAssertNotEqual(oldRegId, alice.client.registrationId)

        // Takeover ends this device's connection on the server; the gateway reconnects by itself.

        // The takeover announces from the new identity. Bob accepts the changed key, as Signal does
        // for an unverified contact, and replaces his session with Alice's device.
        let announce = try await bob.waitForMessage(timeout: 15)
        XCTAssertEqual(announce.type, "DEVICE_ANNOUNCE")
        try await assertDelivers(from: bob, to: alice, "to-new-identity")
        try await assertDelivers(from: alice, to: bob, "from-new-identity")

        alice.disconnectWebSocket()
        bob.disconnectWebSocket()
    }

    private func assertDelivers(from sender: ObscuraTestClient, to receiver: ObscuraTestClient, _ entryId: String,
                                file: StaticString = #filePath, line: UInt = #line) async throws {
        try await sender.client.send(to: [XCTUnwrap(receiver.userId)], modelKey: "testModel", entryId: entryId,
                                     payload: Data(entryId.utf8))
        let wake = try await receiver.waitForMessage(timeout: 15)
        XCTAssertEqual(wake.type, "APP_ENTRY", file: file, line: line)
        let rows = try await receiver.client.inbox.peek()
        XCTAssertTrue(rows.contains { $0.entryId == entryId }, "\(entryId) was not delivered", file: file, line: line)
    }
}
