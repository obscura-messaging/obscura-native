import XCTest
@testable import ObscuraKit

/// Scenario 10: Story attachments — the **bytes path**, against a real server.
///
/// Pix carries an uploaded attachment id inside an opaque APP_ENTRY payload.
/// These tests prove that the entry reaches the recipient and that the
/// recipient can fetch the encrypted bytes it names.
final class StoryAttachmentTests: XCTestCase {

    /// A story entry naming an uploaded attachment reaches the recipient's inbox with the
    /// attachment id intact.
    ///
    /// The payload is opaque to the kit — `mediaRef` is an application field, and the
    /// assertion below reads it back out of the *stored bytes* rather than from any kit-parsed
    /// structure, because the kit does not parse it.
    func testAStoryEntryCarriesItsAttachmentIdToTheRecipientsInbox() async throws {
        let (alice, bob) = try await ObscuraTestClient.registerPairAndBecomeFriends()

        var imageData = Data([0xFF, 0xD8, 0xFF, 0xE0])
        imageData.append(Data(repeating: 0x99, count: 1000))
        let attachmentId = try await alice.api.uploadAttachment(imageData).id
        await rateLimitDelay()

        let entryId = "story_\(UInt64(Date().timeIntervalSince1970 * 1000))_media"
        let payload = try JSONSerialization.data(withJSONObject: [
            "mediaRef": attachmentId,
            "contentType": "image/jpeg",
        ])
        try await alice.client.send(
            to: [bob.userId!], modelKey: "story", entryId: entryId, payload: payload)
        await rateLimitDelay()

        let received = try await bob.waitForMessage(timeout: 15)
        XCTAssertEqual(received.type, "APP_ENTRY")
        XCTAssertEqual(received.sourceUserId, alice.userId!)

        // The inbox row is the delivery — the wake-up above is droppable.
        let row = try await bob.client.inbox.peek(limit: 200).first { $0.entryId == entryId }
        let stored = try XCTUnwrap(row, "the story must be in Bob's inbox")
        XCTAssertEqual(stored.modelKey, "story")
        let decoded = try JSONSerialization.jsonObject(with: stored.payload) as? [String: Any]
        XCTAssertEqual(decoded?["mediaRef"] as? String, attachmentId,
                       "the attachment id must survive the round trip byte for byte")

        bob.disconnectWebSocket()
    }

    /// The recipient can fetch the bytes an entry names. Upload and download are separately
    /// authenticated against the server, so this does not depend on the messaging path at all.
    func testTheRecipientCanDownloadTheAttachmentBytes() async throws {
        let alice = try await ObscuraTestClient.register()
        await rateLimitDelay()
        let bob = try await ObscuraTestClient.register()
        await rateLimitDelay()

        var imageData = Data([0xFF, 0xD8, 0xFF, 0xE0])
        imageData.append(Data(repeating: 0x77, count: 800))
        let attachmentId = try await alice.api.uploadAttachment(imageData).id
        await rateLimitDelay()

        let downloaded = try await bob.api.fetchAttachment(attachmentId)
        XCTAssertEqual(downloaded, imageData, "Downloaded should match uploaded")
    }
}
