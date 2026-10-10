import XCTest
@testable import ObscuraKit

/// `send` — the kit's explicit-audience send.
///
/// 1. the sending device must be excluded from its own fan-out;
/// 2. the sender gets no inbox row, so the app must write its own outgoing entry.
///
/// These determine whether local outgoing writes and incoming delivery share one code path.
final class EntrySendTests: XCTestCase {

    func testASentEntryArrivesInTheRecipientsInboxWithThePayloadUntouched() async throws {
        let alice = try await ObscuraTestClient.register()
        await rateLimitDelay()
        let bob = try await ObscuraTestClient.register()
        await rateLimitDelay()
        try await alice.connectWebSocket()
        try await bob.connectWebSocket()
        await rateLimitDelay()
        try await alice.befriend(bob.userId!)
        _ = try await bob.waitForMessage(timeout: 10)
        try await bob.acceptFriend(alice.userId!)
        _ = try await alice.waitForMessage(timeout: 10)

        let payload = Data(#"{"content":"opaque to the kit","expiresAt":123}"#.utf8)
        try await alice.client.send(
            to: [bob.userId!], modelKey: "directMessage", entryId: "dm_1", payload: payload
        )
        _ = try await bob.waitForMessage(timeout: 10)

        let rows = try await bob.client.inbox.peek()
        XCTAssertEqual(rows.count, 1)
        XCTAssertEqual(rows.first?.modelKey, "directMessage")
        XCTAssertEqual(rows.first?.entryId, "dm_1")
        XCTAssertEqual(rows.first?.payload, payload,
                       "the kit never opens the payload, so it must arrive byte-identical")
        XCTAssertEqual(rows.first?.senderUserId, alice.userId)

        alice.disconnectWebSocket(); bob.disconnectWebSocket()
    }

    /// `getOwnDevices()` includes this device, so a send that does not filter would encrypt a copy
    /// to itself. A single-device sender makes the echo unmissable.
    func testTheSendingDeviceDoesNotReceiveItsOwnEntry() async throws {
        let alice = try await ObscuraTestClient.register()
        await rateLimitDelay()
        let bob = try await ObscuraTestClient.register()
        await rateLimitDelay()
        try await alice.connectWebSocket()
        try await bob.connectWebSocket()
        await rateLimitDelay()
        try await alice.befriend(bob.userId!)
        _ = try await bob.waitForMessage(timeout: 10)
        try await bob.acceptFriend(alice.userId!)
        _ = try await alice.waitForMessage(timeout: 10)

        let depthBefore = try await alice.client.inbox.depth()
        XCTAssertEqual(depthBefore, 0)

        try await alice.client.send(
            to: [bob.userId!], modelKey: "story", entryId: "story_1",
            payload: Data(#"{"content":"mine"}"#.utf8)
        )
        _ = try await bob.waitForMessage(timeout: 10)

        let aliceDepth = try await alice.client.inbox.depth()
        let bobDepth = try await bob.client.inbox.depth()
        XCTAssertEqual(aliceDepth, 0, "a device must not receive the entry it just sent")
        XCTAssertEqual(bobDepth, 1, "...while the recipient still gets it")

        alice.disconnectWebSocket(); bob.disconnectWebSocket()
    }

    /// Nothing loops back locally, so `send` alone leaves the sender with no record of what it sent;
    /// the app writes its own outgoing entry to `entries`. A kit-written copy would require the kit to
    /// pick a model and id from the payload.
    func testSendDoesNotStoreAnythingLocally() async throws {
        let alice = try await ObscuraTestClient.register()
        await rateLimitDelay()
        let bob = try await ObscuraTestClient.register()
        await rateLimitDelay()
        try await alice.connectWebSocket()
        try await bob.connectWebSocket()
        await rateLimitDelay()
        try await alice.befriend(bob.userId!)
        _ = try await bob.waitForMessage(timeout: 10)
        try await bob.acceptFriend(alice.userId!)
        _ = try await alice.waitForMessage(timeout: 10)

        try await alice.client.send(
            to: [bob.userId!], modelKey: "story", entryId: "story_1",
            payload: Data(#"{"content":"mine"}"#.utf8)
        )

        let stored = try await alice.client.entries.all(model: "story")
        XCTAssertTrue(stored.isEmpty,
                      "the kit stores nothing on send; the app writes its own outgoing entry")

        // ...and doing so is a one-liner, which is the point: the app already has the data.
        try await alice.client.entries.put(model: "story", entry: StoredEntry(
            id: "story_1", data: #"{"content":"mine"}"#,
            sentAt: UInt64(Date().timeIntervalSince1970 * 1000),
            authorDeviceId: alice.client.deviceId ?? ""
        ))
        let after = try await alice.client.entries.all(model: "story")
        XCTAssertEqual(after.count, 1)

        alice.disconnectWebSocket(); bob.disconnectWebSocket()
    }

    /// An empty recipient list is "my own devices only", not an error: the caller named an audience,
    /// and it was empty.
    ///
    /// The fixture links a second device through `validateAndApproveLink` so
    /// self-sync has an observable destination.
    func testAnEmptyRecipientListSelfSyncsToTheUsersOtherDevices() async throws {
        let alice1 = try await ObscuraTestClient.register()
        await rateLimitDelay()
        let alice2 = try await ObscuraTestClient.loginAndProvision(alice1.username, deviceName: "Alice Laptop")
        await rateLimitDelay()

        try await alice1.connectWebSocket()
        try await alice2.connectWebSocket()
        await rateLimitDelay()

        // Real link approval: this is what puts BOTH devices in alice1's own-device registry and
        // builds alice1's Signal session to alice2. Without it `send` has nothing to fan out to and
        // the test would be vacuous.
        let code = try XCTUnwrap(alice2.client.generateLinkCode())
        try await alice1.client.validateAndApproveLink(code)
        await rateLimitDelay()

        let ownDevices = await alice1.devices.getOwnDevices()
        XCTAssertEqual(ownDevices.count, 2, "the fixture itself must be real, or nothing below means anything")

        // Drain the approval this kit cannot handle so the assertion below is about the entry and
        // not about link traffic.
        while (try? await alice2.waitForMessage(timeout: 3)) != nil {}
        let depthAfterLinking = try await alice2.client.inbox.depth()

        try await alice1.client.send(
            to: [], modelKey: "profile", entryId: "profile_1",
            payload: Data(#"{"displayName":"alice"}"#.utf8)
        )
        _ = try await alice2.waitForMessage(timeout: 15)

        let alice1Depth = try await alice1.client.inbox.depth()
        let alice2Depth = try await alice2.client.inbox.depth()
        XCTAssertEqual(alice1Depth, 0, "the sending device is excluded from its own fan-out")
        XCTAssertEqual(alice2Depth, depthAfterLinking + 1,
                       "an empty recipient list means 'my own OTHER devices', and one of them exists")

        let rows = try await alice2.client.inbox.peek()
        XCTAssertTrue(rows.contains { $0.modelKey == "profile" && $0.entryId == "profile_1" },
                      "the self-synced entry must arrive intact on the other device")

        alice1.disconnectWebSocket(); alice2.disconnectWebSocket()
    }
}
