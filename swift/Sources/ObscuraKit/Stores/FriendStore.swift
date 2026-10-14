import Foundation
import GRDB

public enum FriendStatus: String, Codable, Sendable {
    case pendingSent = "pending_sent"
    case pendingReceived = "pending_received"
    case accepted = "accepted"
}

public struct Friend: Codable, Sendable, Equatable {
    public let userId: String
    public let username: String
    public let status: FriendStatus
    public let createdAt: UInt64
    public let updatedAt: UInt64
}

actor FriendStore {
    private let db: DatabaseQueue

    init(db: DatabaseQueue) {
        self.db = db
    }

    /// Yields on subscribe and after every committed change to the friend list.
    nonisolated func changes(logger: ObscuraLogger) -> AsyncStream<Void> {
        AsyncStream { continuation in
            let observation = ValueObservation.tracking { db in try Row.fetchAll(db, sql: "SELECT * FROM friends") }
            let cancellable = observation.start(in: db, onError: { error in
                logger.databaseError(store: "friends", operation: "observe", reason: "\(error)")
            }, onChange: { _ in
                continuation.yield()
            })
            continuation.onTermination = { _ in cancellable.cancel() }
        }
    }

    func add(_ userId: String, username: String, status: FriendStatus) async throws {
        try await db.write { db in
            try Self.add(userId, username: username, status: status, in: db)
        }
    }

    static func add(_ userId: String, username: String, status: FriendStatus, in db: Database) throws {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        try db.execute(sql: """
            INSERT OR REPLACE INTO friends (user_id, username, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?)
        """, arguments: [userId, username, status.rawValue, now, now])
    }

    func friend(_ userId: String) async throws -> Friend? {
        try await db.read { db in
            try Self.friend(userId, in: db)
        }
    }

    static func friend(_ userId: String, in db: Database) throws -> Friend? {
        try Row.fetchOne(db, sql: "SELECT * FROM friends WHERE user_id = ?", arguments: [userId]).map(Self.friend)
    }

    func all() async throws -> [Friend] {
        try await db.read { db in try Row.fetchAll(db, sql: "SELECT * FROM friends ORDER BY user_id").map(Self.friend) }
    }

    func updateStatus(_ userId: String, _ status: FriendStatus) async throws {
        try await db.write { db in
            try Self.updateStatus(userId, status, in: db)
        }
    }

    static func updateStatus(_ userId: String, _ status: FriendStatus, in db: Database) throws {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        try db.execute(sql: "UPDATE friends SET status = ?, updated_at = ? WHERE user_id = ?",
                       arguments: [status.rawValue, now, userId])
        guard db.changesCount == 1 else {
            throw DatabaseError(message: "friend \(userId) is missing; status was not updated")
        }
    }

    func removePendingSent(_ userId: String) async throws {
        try await db.write { db in
            try db.execute(sql: "DELETE FROM friends WHERE user_id = ? AND status = ?",
                           arguments: [userId, FriendStatus.pendingSent.rawValue])
        }
    }

    /// Forgets the friendship and the friend's devices.
    func remove(_ userId: String) async throws {
        try await db.write { db in
            try db.execute(sql: "DELETE FROM friends WHERE user_id = ?", arguments: [userId])
            try db.execute(sql: "DELETE FROM devices WHERE user_id = ?", arguments: [userId])
        }
    }

    private static func friend(_ row: Row) throws -> Friend {
        guard let status = FriendStatus(rawValue: row["status"]) else {
            throw DatabaseError(message: "friend \(row["user_id"] as String) has unknown status \(row["status"] as String)")
        }
        return Friend(userId: row["user_id"], username: row["username"], status: status,
                      createdAt: UInt64(row["created_at"] as Int64), updatedAt: UInt64(row["updated_at"] as Int64))
    }
}
