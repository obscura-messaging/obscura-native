import Foundation
import GRDB

/// One drained inbox row, as the app sees it.
///
/// `payload` is opaque bytes the kit never parsed. The `AppEntry`-derived fields are `nil` for every
/// other kind, including an unknown arm — there is no `AppEntry` to derive them from.
public struct InboxRecord: Sendable, Equatable {
    public let id: Int64
    public let kind: String
    public let senderUserId: String
    public let senderDeviceId: String?
    public let modelKey: String?
    public let entryId: String?
    public let sentAt: UInt64?
    public let payload: Data

    public init(
        id: Int64 = 0,
        kind: String,
        senderUserId: String,
        senderDeviceId: String? = nil,
        modelKey: String? = nil,
        entryId: String? = nil,
        sentAt: UInt64? = nil,
        payload: Data
    ) {
        self.id = id
        self.kind = kind
        self.senderUserId = senderUserId
        self.senderDeviceId = senderDeviceId
        self.modelKey = modelKey
        self.entryId = entryId
        self.sentAt = sentAt
        self.payload = payload
    }
}

struct InboxInsert: Sendable {
    let envelopeId: String
    let kind: String
    let senderUserId: String
    let senderDeviceId: String?
    let modelKey: String?
    let entryId: String?
    let sentAt: UInt64?
    let payload: Data
}

/// The durable inbox: opaque payloads persisted before the server copy is acked, because the app
/// may not be running to receive an event. Only the receive path writes it; the app drains it.
public actor InboxStore {
    private let db: DatabaseQueue

    /// Reports a discard to the security log. Taken at construction rather than set afterwards: a
    /// store that is reachable before its hook is wired could lose a discard silently.
    private let onDiscard: (@Sendable ([Int64], String) -> Void)?

    public init(db: DatabaseQueue, onDiscard: (@Sendable ([Int64], String) -> Void)? = nil) throws {
        self.db = db
        self.onDiscard = onDiscard
        try ObscuraSchema.migrate(db)
    }

    public init(onDiscard: (@Sendable ([Int64], String) -> Void)? = nil) throws {
        self.db = try DatabaseQueue()
        self.onDiscard = onDiscard
        try db.write { db in try db.execute(sql: "PRAGMA secure_delete = ON") }
        try ObscuraSchema.migrate(db)
    }

    /// Persist a decrypted message. **Kit-internal**: called from the receive loop before the ack,
    /// never by the app.
    ///
    /// Throws if the write fails, which is the point — the caller must not ack what is not stored.
    ///
    /// Returns `true` if a row was inserted, `false` if `envelopeId` was already present. A `false`
    /// is a *successful* redelivery absorption, not an error: the message is already durably held,
    /// so the caller should ack exactly as it would after a fresh insert. Acking is what stops the
    /// server sending it a third time.
    @discardableResult
    func put(_ record: InboxInsert) async throws -> Bool {
        try await db.write { db in
            let existedBefore = try Bool.fetchOne(
                db, sql: "SELECT EXISTS(SELECT 1 FROM inbox_rows WHERE envelope_id = ?)",
                arguments: [record.envelopeId]) ?? false

            try db.execute(sql: """
                INSERT OR IGNORE INTO inbox_rows (
                    envelope_id, kind, sender_user_id, sender_device_id,
                    model_key, entry_id, sent_at, payload
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, arguments: [
                record.envelopeId, record.kind, record.senderUserId,
                record.senderDeviceId, record.modelKey, record.entryId,
                record.sentAt.map { Int64($0) }, record.payload,
            ])

            // Assert the postcondition the ack depends on, rather than inferring it from a row
            // count. `changesCount` tells you "did OR IGNORE suppress something" — and OR IGNORE
            // suppresses EVERY constraint, not just the `envelope_id UNIQUE` it is documented
            // against. A NOT NULL or CHECK violation would report exactly like a redelivery, and the
            // caller ACKS on a redelivery, so the server would delete a message never stored.
            let existsNow = try Bool.fetchOne(
                db, sql: "SELECT EXISTS(SELECT 1 FROM inbox_rows WHERE envelope_id = ?)",
                arguments: [record.envelopeId]) ?? false
            guard existsNow else {
                throw DatabaseError(message: "inbox row for envelope \(record.envelopeId) is absent "
                    + "after insert; refusing to report it as stored")
            }
            return !existedBefore
        }
    }

    /// The next rows to process, in drain order, oldest first.
    ///
    /// **Side-effect free.** Peeking twice without consuming returns the same rows — that is the
    /// crash-safety property, not a bug: an app that dies mid-drain reprocesses, and the merge rules
    /// downstream are idempotent so that reprocessing converges.
    public func peek(limit: Int = 50) async throws -> [InboxRecord] {
        try await db.read { db in
            try Row.fetchAll(db, sql: """
                SELECT * FROM inbox_rows ORDER BY id ASC LIMIT ?
            """, arguments: [limit]).map { row in
                InboxRecord(
                    id: row["id"],
                    kind: row["kind"],
                    senderUserId: row["sender_user_id"],
                    senderDeviceId: row["sender_device_id"],
                    modelKey: row["model_key"],
                    entryId: row["entry_id"],
                    // Saturating, not `UInt64(_:)`: that TRAPS on a negative value, which would be a
                    // hard crash in `peek` — and a negative `sent_at` is reachable, because
                    // `AppEntry.timestamp` is proto3 `uint64` and Kotlin's protobuf surfaces it as
                    // a signed Long. A row written by a peer kit must never be able to crash a drain.
                    sentAt: (row["sent_at"] as Int64?).map { $0 < 0 ? 0 : UInt64($0) },
                    payload: row["payload"]
                )
            }
        }
    }

    /// Drop rows the app has durably processed.
    ///
    /// Idempotent, and a subset is fine — partial progress is normal, not an error path.
    public func consume(_ ids: [Int64]) async throws {
        guard !ids.isEmpty else { return }
        // Chunked because each id binds one SQL variable and SQLite caps that at 999 on older
        // builds. The app chooses the batch size, so a large `peek` followed by `consume` would
        // throw "too many SQL variables" — exactly when a backlog exists, i.e. the one situation
        // where the drain must not stall.
        for chunk in stride(from: 0, to: ids.count, by: deleteChunk).map({
            Array(ids[$0..<min($0 + deleteChunk, ids.count)])
        }) {
            try await db.write { db in
                try db.execute(
                    sql: "DELETE FROM inbox_rows WHERE id IN (\(databaseQuestionMarks(count: chunk.count)))",
                    arguments: StatementArguments(chunk))
            }
        }
    }

    /// Comfortably under SQLite's 999-variable floor.
    private let deleteChunk = 500

    /// Drop rows the app declares it can **never** process.
    ///
    /// This is deliberate data loss: the server's copy is already gone. It is separate from
    /// ``consume(_:)`` so it can be logged as a security-relevant event.
    public func discard(_ ids: [Int64], reason: String) async throws {
        guard !ids.isEmpty else { return }
        try await consume(ids)
        onDiscard?(ids, reason)
    }

    /// How many rows are waiting.
    ///
    /// Unbounded growth means the app has stopped draining; the app is expected to surface this
    /// past a threshold before the server's queue fills.
    public func depth() async throws -> Int {
        try await db.read { db in
            try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM inbox_rows") ?? 0
        }
    }

    /// Destroy every row.
    ///
    /// For device wipe only, so decrypted plaintext can be destroyed. It takes no selector so it
    /// cannot become an eviction policy.
    func wipe() async throws {
        try await db.write { db in
            try db.execute(sql: "DELETE FROM inbox_rows")
        }
    }
}

/// `?, ?, ?` for an `IN` clause. GRDB has no variadic binding for `IN`, and string-interpolating the
/// ids themselves would be an injection site even for integers.
private func databaseQuestionMarks(count: Int) -> String {
    Array(repeating: "?", count: count).joined(separator: ", ")
}
