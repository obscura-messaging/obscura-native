import Foundation
import GRDB

/// One stored entry. `data` and `localMetadata` are opaque to the kit; `localMetadata` never leaves
/// this device.
public struct StoredEntry: Sendable, Equatable {
    public let id: String
    public let data: String
    public let sentAt: UInt64
    public let authorDeviceId: String
    public let localMetadata: String?

    public init(id: String, data: String, sentAt: UInt64, authorDeviceId: String, localMetadata: String?) {
        self.id = id
        self.data = data
        self.sentAt = sentAt
        self.authorDeviceId = authorDeviceId
        self.localMetadata = localMetadata
    }
}

/// The app's entries, keyed by `(model, id)`. `put` is a blind upsert; the app resolves merges.
public actor EntryStore {
    private let db: DatabaseQueue

    init(db: DatabaseQueue) {
        self.db = db
    }

    public func put(model: String, entry: StoredEntry) async throws {
        try await db.write { db in try Self.put(model: model, entry: entry, in: db) }
    }

    private static func put(model: String, entry: StoredEntry, in db: Database) throws {
        try db.execute(sql: """
            INSERT OR REPLACE INTO model_entries (model_name, id, data, timestamp, author_device_id, local_metadata)
            VALUES (?, ?, ?, ?, ?, ?)
        """, arguments: [
            model, entry.id, entry.data, Int64(clamping: entry.sentAt), entry.authorDeviceId, entry.localMetadata,
        ])
    }

    public func get(model: String, ids: [String]) async throws -> [StoredEntry] {
        if ids.isEmpty { return [] }
        return try await db.read { db in
            try Row.fetchAll(db, sql: """
                SELECT * FROM model_entries WHERE model_name = ? AND id IN (\(questionMarks(ids.count)))
            """, arguments: StatementArguments([model] + ids)).map(Self.entry)
        }
    }

    public func all(model: String) async throws -> [StoredEntry] {
        try await db.read { db in
            try Row.fetchAll(db, sql: "SELECT * FROM model_entries WHERE model_name = ?", arguments: [model]).map(Self.entry)
        }
    }

    /// Zeroes freed pages and truncates the WAL. Each device erases its own copy.
    public func erase(model: String, ids: [String]) async throws {
        if ids.isEmpty { return }
        try await db.write { db in
            try db.execute(sql: "PRAGMA secure_delete = ON")
            for offset in stride(from: 0, to: ids.count, by: 500) {
                let chunk = Array(ids[offset..<min(offset + 500, ids.count)])
                try db.execute(sql: "DELETE FROM model_entries WHERE model_name = ? AND id IN (\(questionMarks(chunk.count)))",
                               arguments: StatementArguments([model] + chunk))
            }
        }
        try await ObscuraDatabase.truncateWAL(db)
    }

    /// Every entry of every model, without local metadata, for a newly linked device.
    func snapshot() async throws -> Obscura_Client_V1_EntrySnapshot {
        try await db.read { db in
            var snapshot = Obscura_Client_V1_EntrySnapshot()
            snapshot.entries = try Row.fetchAll(db, sql: "SELECT * FROM model_entries ORDER BY model_name, id").map { row in
                let entry = Self.entry(row)
                var exported = Obscura_Client_V1_SnapshotEntry()
                exported.model = row["model_name"]
                exported.id = entry.id
                exported.data = Data(entry.data.utf8)
                exported.sentAt = entry.sentAt
                exported.authorDeviceID = entry.authorDeviceId
                return exported
            }
            return snapshot
        }
    }

    func importSnapshot(_ snapshot: Obscura_Client_V1_EntrySnapshot) async throws {
        let entries = try snapshot.entries.map { exported in
            guard let data = String(data: exported.data, encoding: .utf8) else {
                throw ObscuraError.protocolViolation("history entry \(exported.model)/\(exported.id) is not UTF-8")
            }
            return (exported.model, StoredEntry(id: exported.id, data: data, sentAt: exported.sentAt,
                                                authorDeviceId: exported.authorDeviceID, localMetadata: nil))
        }
        try await db.write { db in
            for (model, entry) in entries { try Self.put(model: model, entry: entry, in: db) }
        }
    }

    private static func entry(_ row: Row) -> StoredEntry {
        StoredEntry(
            id: row["id"],
            data: row["data"],
            sentAt: UInt64(max(0, row["timestamp"] as Int64)),
            authorDeviceId: row["author_device_id"],
            localMetadata: row["local_metadata"]
        )
    }
}
