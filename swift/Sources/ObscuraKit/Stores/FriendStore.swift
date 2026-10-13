import Foundation
import GRDB

public enum FriendStatus: String, Codable, Sendable {
    case pendingSent = "pending_sent"
    case pendingReceived = "pending_received"
    case accepted = "accepted"
}

public struct Friend: Codable, Sendable, Equatable {
    public var userId: String
    public var username: String
    public var status: FriendStatus
    public var createdAt: UInt64
    public var updatedAt: UInt64

    /// A friend's devices live in ``DeviceStore``.
    public init(userId: String, username: String, status: FriendStatus) {
        self.userId = userId
        self.username = username
        self.status = status
        self.createdAt = UInt64(Date().timeIntervalSince1970 * 1000)
        self.updatedAt = self.createdAt
    }
}

public actor FriendStore {
    private let db: DatabaseQueue

    /// Exposed for GRDB ValueObservation (read-only observation from any isolation)
    public nonisolated var dbQueue: DatabaseQueue { db }

    public init(db: DatabaseQueue) throws {
        self.db = db
        try ObscuraSchema.migrate(db)
    }

    public init() throws {
        self.db = try DatabaseQueue()
        try db.write { db in try db.execute(sql: "PRAGMA secure_delete = ON") }
        try ObscuraSchema.migrate(db)
    }

    // MARK: - Reactive Streams (GRDB ValueObservation)

    /// Canonical internal observation of accepted friends.
    nonisolated func observeAccepted() -> AsyncValueObservation<[Friend]> {
        let observation = ValueObservation.tracking { db -> [Friend] in
            let rows = try Row.fetchAll(db, sql: "SELECT * FROM friends WHERE status = ?",
                                        arguments: [FriendStatus.accepted.rawValue])
            return rows.compactMap { Self.rowToFriend($0) }
        }
        return AsyncValueObservation(observation: observation, in: db)
    }

    /// Canonical internal observation used to drive the payload-free aggregate wake event.
    nonisolated func observeAll() -> AsyncValueObservation<[Friend]> {
        let observation = ValueObservation.tracking { db -> [Friend] in
            let rows = try Row.fetchAll(db, sql: "SELECT * FROM friends")
            return rows.compactMap { Self.rowToFriend($0) }
        }
        return AsyncValueObservation(observation: observation, in: db)
    }


    // A durable write that backs an acked message must be able to fail loudly,
    // so the envelope loop skips the ack instead of deleting an un-persisted message server-side.
    public func add(_ userId: String, _ username: String, status: FriendStatus) async throws {
        let now = UInt64(Date().timeIntervalSince1970 * 1000)
        try await db.write { db in
            try db.execute(sql: """
                INSERT OR REPLACE INTO friends (
                    user_id, username, status, devices_updated_at, created_at, updated_at
                ) VALUES (?, ?, ?, 0, ?, ?)
            """, arguments: [userId, username, status.rawValue, now, now])
        }
    }

    public func getFriend(_ userId: String) async -> Friend? {
        try? await db.read { db -> Friend? in
            guard let row = try Row.fetchOne(db, sql: "SELECT * FROM friends WHERE user_id = ?", arguments: [userId]) else { return nil }
            return Self.rowToFriend(row)
        }
    }

    public func getAccepted() async -> [Friend] {
        (try? await db.read { db -> [Friend] in
            try Row.fetchAll(db, sql: "SELECT * FROM friends WHERE status = ?", arguments: [FriendStatus.accepted.rawValue])
                .compactMap { Self.rowToFriend($0) }
        }) ?? []
    }

    public func updateStatus(_ userId: String, _ newStatus: FriendStatus) async throws {
        let now = UInt64(Date().timeIntervalSince1970 * 1000)
        try await db.write { db in
            try db.execute(sql: "UPDATE friends SET status = ?, updated_at = ? WHERE user_id = ?",
                           arguments: [newStatus.rawValue, now, userId])
            guard db.changesCount == 1 else {
                throw DatabaseError(message: "friend \(userId) is missing; status was not updated")
            }
        }
    }

    public func remove(_ userId: String) async throws {
        try await db.write { db in
            try db.execute(sql: "DELETE FROM friends WHERE user_id = ?", arguments: [userId])
        }
    }

    public func clearAll() async {
        try? await db.write { db in
            try db.execute(sql: "DELETE FROM friends")
        }
    }

    public func getAll() async -> [Friend] {
        (try? await db.read { db -> [Friend] in
            try Row.fetchAll(db, sql: "SELECT * FROM friends").compactMap { Self.rowToFriend($0) }
        }) ?? []
    }

    public func isFriend(_ userId: String) async -> Bool {
        let friend = await getFriend(userId)
        return friend?.status == .accepted
    }

    private static func rowToFriend(_ row: Row) -> Friend? {
        var friend = Friend(
            userId: row["user_id"],
            username: row["username"],
            status: FriendStatus(rawValue: row["status"]) ?? .pendingSent
        )
        friend.createdAt = UInt64(row["created_at"] as Int64)
        friend.updatedAt = UInt64(row["updated_at"] as Int64)
        return friend
    }
}
