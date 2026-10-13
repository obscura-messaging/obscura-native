import Foundation
import XCTest
@testable import ObscuraKit

/// The client's local contract: guards, and how each received payload class is handled.
final class ClientTests: XCTestCase {
    private func signedIn() async throws -> ObscuraClient {
        let db = try ObscuraDatabase.inMemory()
        try PersistentSignalStore(db: db, logger: SilentLogger()).install(SignalKeys.generate())
        try await DeviceStore(db: db).setLocalDeviceId("my-device")
        let client = try makeClient(database: db, storage: MemorySessionStorage([
            "token": SessionRestoreTests.jwt(exp: Int(Date().timeIntervalSince1970) + 3600, userId: "me", deviceId: "my-device"), "refreshToken": "rt",
            "userId": "me", "deviceId": "my-device", "username": "me",
        ]))
        // The server is unreachable, so the session is restored offline.
        let restored = await client.restorePersistedSession()
        XCTAssertTrue(restored)
        return client
    }

    private func message(_ build: (inout Obscura_Client_V1_ClientMessage) -> Void) -> Obscura_Client_V1_ClientMessage {
        var message = Obscura_Client_V1_ClientMessage()
        build(&message)
        return message
    }

    private func route(_ client: ObscuraClient, _ message: Obscura_Client_V1_ClientMessage, envelope: String = UUID().uuidString,
                       from sender: String = "peer") async throws -> String? {
        try await client.route(message, envelopeId: envelope, senderUserId: sender, senderDeviceId: "\(sender)-device")
    }

    // MARK: - Guards

    func testSignedOutOperationsThrowNotAuthenticated() async throws {
        let client = try makeClient()

        await assertThrows(.notAuthenticated) { try await client.connect() }
        await assertThrows(.notAuthenticated) { _ = try await client.friendCode() }
        await assertThrows(.notAuthenticated) { try await client.sendTyping(to: ["x"], contextId: "c", state: .started) }
        await assertThrows(.notAuthenticated) {
            _ = try await client.send(to: ["x"], modelKey: "m", entryId: "e", sentAt: 1, payload: Data(), acceptedDeviceIds: [])
        }
        await assertThrows(.notAuthenticated) { try await client.addFriendByCode(FriendCode.encode(userId: "u", username: "n")) }
    }

    func testABlankTypingContextIsRejected() async throws {
        let client = try await signedIn()
        await assertThrows(.invalidArgument("blank contextId")) {
            try await client.sendTyping(to: ["x"], contextId: " \n", state: .started)
        }

        func testProvisioningAnExistingLocalDeviceRequiresAnExplicitWipe() async throws {
            let client = try await signedIn()
            let error = ObscuraError.invalidArgument("local device exists; wipeDevice before provisioning")
            await assertThrows(error) { try await client.register("new-account", "password") }
            await assertThrows(error) { try await client.loginAndProvision("new-account", "password") }
            XCTAssertTrue(try client.signalStore.hasIdentity())
            let deviceId = try await client.devices.localDeviceId()
            XCTAssertEqual(deviceId, "my-device")
        }
    }

    func testFriendCodeEncodesThisUser() async throws {
        let client = try await signedIn()
        let decoded = try FriendCode.decode(await client.friendCode())
        XCTAssertEqual(decoded.userId, "me")
        XCTAssertEqual(decoded.username, "me")
    }

    func testAnInvalidFriendCodeIsReportedAsSuch() async throws {
        let client = try await signedIn()
        await assertThrows(.invalidFriendCode) { try await client.addFriendByCode("garbage!") }
    }

    // MARK: - Receive classification

    func testANewAppEntryIsInboxedAndWakesWithItsModelKeyButARedeliveryDoesNot() async throws {
        let client = try await signedIn()
        let entry = message {
            $0.appEntry.model = "story"
            $0.appEntry.id = "s1"
            $0.appEntry.timestamp = UInt64.max
            $0.appEntry.data = Data([1, 2, 3])
        }

        let first = try await route(client, entry, envelope: "env-1")
        let redelivery = try await route(client, entry, envelope: "env-1")

        XCTAssertEqual(first, "story")
        XCTAssertNil(redelivery)
        let rows = try await client.inbox.peek(limit: 10)
        XCTAssertEqual(rows.count, 1)
        XCTAssertEqual(rows[0].kind, "APP_ENTRY")
        XCTAssertEqual(rows[0].senderDeviceId, "peer-device")
        XCTAssertEqual(rows[0].payload, Data([1, 2, 3]))
        XCTAssertLessThanOrEqual(rows[0].sentAt!, UInt64(Date().timeIntervalSince1970 * 1000) + 60_000)
    }

    func testAnUnknownArmIsInboxedWholeAsUnknownWithoutAWakeUp() async throws {
        let client = try await signedIn()
        // Field 4, the removed device link approval arm, as an empty message.
        let unknown = try Obscura_Client_V1_ClientMessage(serializedBytes: Data([0x22, 0x00]))

        let wake = try await route(client, unknown)

        XCTAssertNil(wake)
        let rows = try await client.inbox.peek(limit: 10)
        XCTAssertEqual(rows.map(\.kind), ["UNKNOWN"])
        XCTAssertNil(rows[0].modelKey)
        XCTAssertEqual(rows[0].payload, try unknown.serializedData())
    }

    func testAFriendRequestFromAStrangerIsPendingAndAKnownPeersChangesNothing() async throws {
        let client = try await signedIn()
        let request = message { $0.friendRequest.username = "mallory" }

        _ = try await route(client, request, from: "stranger")
        try await client.friends.add("friend", username: "real name", status: .accepted)
        _ = try await route(client, message { $0.friendRequest.username = "Mum" }, from: "friend")

        let stranger = try await client.friends.friend("stranger")
        let friend = try await client.friends.friend("friend")
        XCTAssertEqual(stranger?.status, .pendingReceived)
        XCTAssertEqual(stranger?.username, "mallory")
        XCTAssertEqual(friend?.status, .accepted)
        XCTAssertEqual(friend?.username, "real name")
        let depth = try await client.inbox.depth()
        XCTAssertEqual(depth, 0, "friend traffic is kit-internal")
    }

    func testAnAcceptPromotesOnlyARequestThisUserSent() async throws {
        let client = try await signedIn()
        try await client.friends.add("invited", username: "bob", status: .pendingSent)
        try await client.friends.add("asker", username: "carol", status: .pendingReceived)
        let accept = message { $0.friendAccept = Obscura_Client_V1_FriendAccept() }

        for sender in ["invited", "asker", "stranger"] { _ = try await route(client, accept, from: sender) }

        let invited = try await client.friends.friend("invited")
        let asker = try await client.friends.friend("asker")
        let stranger = try await client.friends.friend("stranger")
        XCTAssertEqual(invited?.status, .accepted)
        XCTAssertEqual(asker?.status, .pendingReceived)
        XCTAssertNil(stranger)
    }

    func testTypingSignalsDriveTheTrackerAndNeverTheInbox() async throws {
        let client = try await signedIn()
        let started = message {
            $0.typingSignal.contextID = "c"
            $0.typingSignal.state = .started
        }
        let unspecified = message {
            $0.typingSignal.contextID = "c"
            $0.typingSignal.state = .unspecified
        }

        _ = try await route(client, unspecified, from: "carol")
        _ = try await route(client, started, from: "alice")
        _ = try await route(client, started, from: "alice")

        var typers = client.observeTyping(contextId: "c").makeAsyncIterator()
        let current = await typers.next()
        XCTAssertEqual(current, ["alice"])
        let depth = try await client.inbox.depth()
        XCTAssertEqual(depth, 0)

        try await client.logout()
        let afterLogout = await typers.next()
        XCTAssertEqual(afterLogout, [], "typing state is cleared on logout")
    }

    private func assertThrows(_ expected: ObscuraError, file: StaticString = #filePath, line: UInt = #line,
                              _ body: () async throws -> Void) async {
        do {
            try await body()
            XCTFail("expected \(expected)", file: file, line: line)
        } catch {
            XCTAssertEqual(error as? ObscuraError, expected, file: file, line: line)
        }
    }
}
