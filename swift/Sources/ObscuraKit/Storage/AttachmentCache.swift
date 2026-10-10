import Foundation
import GRDB

/// In-DB cache for decrypted attachment bytes.
/// Per-user (lives in the user's encrypted GRDB database).
/// Invisible to consumers — same downloadDecryptedAttachment() API.
public class AttachmentCache {
    private let db: DatabaseQueue
    private static let maxCacheBytes: Int64 = 50 * 1024 * 1024 // 50MB

    public init(db: DatabaseQueue) throws {
        self.db = db
        try ObscuraSchema.migrate(db)
    }


    /// Check cache for attachment. Returns decrypted bytes or nil.
    public func get(_ attachmentId: String) async -> Data? {
        try? await db.read { db in
            try Data.fetchOne(db, sql: "SELECT plaintext FROM attachment_cache WHERE attachment_id = ?",
                              arguments: [attachmentId])
        }
    }

    /// Store decrypted bytes in cache. Evicts oldest if over size limit.
    public func put(_ attachmentId: String, plaintext: Data) async {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        try? await db.write { db in
            try db.execute(sql: """
                INSERT OR REPLACE INTO attachment_cache (attachment_id, plaintext, size_bytes, cached_at)
                VALUES (?, ?, ?, ?)
            """, arguments: [attachmentId, plaintext, plaintext.count, now])
        }

        // Evict if over limit
        let totalSize = (try? await db.read { db in
            try Int64.fetchOne(db, sql: "SELECT COALESCE(SUM(size_bytes), 0) FROM attachment_cache")
        }) ?? 0

        if totalSize > Self.maxCacheBytes {
            try? await db.write { db in
                try db.execute(sql: """
                    DELETE FROM attachment_cache WHERE attachment_id IN
                    (SELECT attachment_id FROM attachment_cache ORDER BY cached_at ASC LIMIT 10)
                """)
            }
        }
    }

    /// Remove one attachment's decrypted bytes, unrecoverably: deleted under `secure_delete`, then
    /// the WAL is truncated (as for `EntryStore.erase`).
    public func remove(_ attachmentId: String) async throws {
        try await db.write { db in
            try db.execute(sql: "PRAGMA secure_delete = ON")
            try db.execute(sql: "DELETE FROM attachment_cache WHERE attachment_id = ?",
                           arguments: [attachmentId])
        }
        try await db.writeWithoutTransaction { db in
            try db.execute(sql: "PRAGMA wal_checkpoint(TRUNCATE)")
        }
    }

    /// Clear all cached attachments.
    public func clearAll() async {
        try? await db.write { db in
            try db.execute(sql: "DELETE FROM attachment_cache")
        }
    }
}
