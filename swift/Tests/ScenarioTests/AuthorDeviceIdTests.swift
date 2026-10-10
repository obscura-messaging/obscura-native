import XCTest
@testable import ObscuraKit

/// `Envelope.sender_device_id` is stamped server-side from the sender's device-scoped JWT, and
/// derives attribution from the address of the session that decrypted: a valid MAC proves possession
/// of that session's chain key, which only the sender's device holds.
///
/// This asserts the attribution is the sender's **real device UUID**, on the wake-up and in the
/// durable inbox, and emphatically **not** the user id.
final class AuthorDeviceIdTests: XCTestCase {

    func testAuthorDeviceIdIsTheSendersRealDeviceUUIDNeverTheUserId() async throws {
        let (alice, bob) = try await ObscuraTestClient.registerPairAndBecomeFriends()

        let bobDeviceId = try XCTUnwrap(bob.deviceId, "Bob must have a device id")
        let bobUserId = try XCTUnwrap(bob.userId, "Bob must have a user id")
        XCTAssertNotEqual(bobDeviceId, bobUserId,
            "device UUID and user UUID must differ, or this test cannot distinguish them")

        try await bob.client.send(
            to: [alice.userId!], modelKey: "testModel", entryId: "attributed-entry",
            payload: Data("attribute me correctly".utf8)
        )

        let received = try await alice.waitForMessage(timeout: 15)
        XCTAssertEqual(received.type, "APP_ENTRY")
        XCTAssertEqual(received.sourceUserId, bobUserId, "sourceUserId is Bob's USER id")

        // The wake-up carries the sender's real device UUID, not the user id.
        let senderDeviceId = try XCTUnwrap(received.senderDeviceId,
            "senderDeviceId must be populated")
        XCTAssertEqual(senderDeviceId, bobDeviceId, "senderDeviceId must be Bob's REAL device UUID")
        XCTAssertNotEqual(senderDeviceId, bobUserId,
            "senderDeviceId must not be the user id")

        try await Task.sleep(nanoseconds: 500_000_000)

        // The DURABLE inbox records the honest device id too. Attribution that is right
        // in the wake-up and wrong on disk is still wrong, and the app reads the store.
        let persisted = try await alice.client.inbox.peek()
        let row = try XCTUnwrap(persisted.first { $0.entryId == "attributed-entry" },
            "entry must be persisted in Alice's inbox")
        XCTAssertEqual(row.senderDeviceId, bobDeviceId,
            "persisted authorDeviceId must be Bob's REAL device UUID")
        XCTAssertNotEqual(row.senderDeviceId, bobUserId,
            "persisted authorDeviceId must NOT be the user id")

        alice.disconnectWebSocket()
        bob.disconnectWebSocket()
    }
}
