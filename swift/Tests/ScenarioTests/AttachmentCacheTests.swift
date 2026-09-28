import XCTest
import GRDB
@testable import ObscuraKit

/// `AttachmentCache.remove` (behind `ObscuraClient.purgeAttachment`): one attachment's decrypted
/// bytes are gone from the database files, WAL included. Mirrors Kotlin `AttachmentPurgeTest`.
final class AttachmentCacheTests: XCTestCase {

    func testRemoveDeletesOnlyTheNamedAttachmentAndLeavesNoPlaintextOnDisk() async throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let path = dir.appendingPathComponent("purge.db").path

        var config = Configuration()
        config.prepareDatabase { db in try db.execute(sql: "PRAGMA journal_mode = WAL") }
        let cache = try AttachmentCache(db: try DatabaseQueue(path: path, configuration: config))
        let secret = "PLAINTEXT-PIX-5e2b"
        await cache.put("att_1", plaintext: Data(secret.utf8))
        await cache.put("att_2", plaintext: Data("keep".utf8))
        func onDisk() -> Bool {
            [path, path + "-wal"].contains { file in
                guard let data = FileManager.default.contents(atPath: file) else { return false }
                return data.range(of: Data(secret.utf8)) != nil
            }
        }
        XCTAssertTrue(onDisk(), "control: the plaintext must be on disk before removal")

        try await cache.remove("att_1")

        let removed = await cache.get("att_1")
        let kept = await cache.get("att_2")
        XCTAssertNil(removed)
        XCTAssertNotNil(kept)
        XCTAssertFalse(onDisk(), "removed plaintext must not survive in the database or its WAL")
    }

    func testRemovingAnUncachedAttachmentIsANoOp() async throws {
        let cache = try AttachmentCache(db: try DatabaseQueue())
        try await cache.remove("never_cached")
    }
}
