import XCTest
@testable import ObscuraKit

/// The friend graph is the only source of display names, including notification names. Genuine encrypted messages must not
/// let an established friend rewrite its own record or a stranger create an
/// accepted friendship through an unsolicited response.
final class FriendGraphIntegrityTests: XCTestCase {

    func testAcceptedFriendCannotRenameItselfOrResetItsStatus() async throws {
        let (alice, mallory) = try await ObscuraTestClient.registerPairAndBecomeFriends()

        let malloryId = try XCTUnwrap(mallory.userId)
        // NB: hoist the await — XCTUnwrap takes an autoclosure, which cannot be async.
        let beforeRecord = await alice.friends.getFriend(malloryId)
        let before = try XCTUnwrap(beforeRecord, "Mallory must be in Alice's graph before the attack")
        XCTAssertEqual(before.status, .accepted, "precondition: accepted")
        let realName = before.username

        // THE ATTACK: a second FriendRequest from an established friend, claiming a new name.
        var spoof = Obscura_Client_V1_ClientMessage()
        var req = Obscura_Client_V1_FriendRequest()
        req.username = "Mum"
        spoof.friendRequest = req
        spoof.timestamp = UInt64(Date().timeIntervalSince1970 * 1000)
        try await mallory.sendRaw(to: try XCTUnwrap(alice.userId), try spoof.serializedData())

        _ = try? await alice.waitForMessage(timeout: 10)
        try await Task.sleep(nanoseconds: 500_000_000)

        let afterRecord = await alice.friends.getFriend(malloryId)
        let after = try XCTUnwrap(afterRecord, "Mallory must still be in the graph")
        print("  stored name before='\(realName)' after='\(after.username)' status=\(after.status.rawValue)")

        XCTAssertEqual(after.username, realName,
            "a peer MUST NOT be able to rewrite its own display name — that name reaches the lock screen")
        XCTAssertNotEqual(after.username, "Mum", "the payload-supplied name must be ignored for a known peer")
        XCTAssertEqual(after.status, .accepted,
            "a peer MUST NOT be able to reset its own status — that silently removes it from fan-out")

        alice.disconnectWebSocket()
        mallory.disconnectWebSocket()
    }

    func testStrangerCannotSelfAcceptIntoTheFriendGraph() async throws {
        let alice = try await ObscuraTestClient.register()
        await rateLimitDelay()
        let stranger = try await ObscuraTestClient.register()
        await rateLimitDelay()
        try await alice.connectWebSocket()
        try await stranger.connectWebSocket()
        await rateLimitDelay()

        // No friendship in either direction: the stranger simply claims Alice accepted them.
        var forged = Obscura_Client_V1_ClientMessage()
        forged.friendAccept = Obscura_Client_V1_FriendAccept()
        forged.timestamp = UInt64(Date().timeIntervalSince1970 * 1000)
        try await stranger.sendRaw(to: try XCTUnwrap(alice.userId), try forged.serializedData())

        _ = try? await alice.waitForMessage(timeout: 10)
        try await Task.sleep(nanoseconds: 500_000_000)

        let injected = await alice.friends.getFriend(try XCTUnwrap(stranger.userId))
        print("  alice's graph after unsolicited acceptance: \(await alice.friends.getAll().map { ($0.username, $0.status.rawValue) })")
        XCTAssertFalse(injected?.status == .accepted,
            "an unsolicited FRIEND_ACCEPT MUST NOT create an accepted friend — got \(String(describing: injected))")

        alice.disconnectWebSocket()
        stranger.disconnectWebSocket()
    }
}
