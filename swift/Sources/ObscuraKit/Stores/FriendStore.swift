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
    public var devices: [[String: String]]
    public var devicesUpdatedAt: UInt64
    public var createdAt: UInt64
    public var updatedAt: UInt64

    public init(userId: String, username: String, status: FriendStatus, devices: [[String: String]] = []) {
        self.userId = userId
        self.username = username
        self.status = status
        self.devices = devices
        self.devicesUpdatedAt = 0
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
    public func add(_ userId: String, _ username: String, status: FriendStatus, devices: [[String: String]] = []) async throws {
        let now = UInt64(Date().timeIntervalSince1970 * 1000)
        let devicesJson = (try? JSONSerialization.data(withJSONObject: devices)).flatMap { String(data: $0, encoding: .utf8) } ?? "[]"

        try await db.write { db in
            try db.execute(sql: """
                INSERT OR REPLACE INTO friends (
                    user_id, username, status, devices, devices_updated_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 0, ?, ?)
            """, arguments: [userId, username, status.rawValue, devicesJson, now, now])
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

    public func updateDevices(_ userId: String, devices: [[String: String]], timestamp: UInt64? = nil) async throws {
        // Saturating, not `Int64(_:)`: that TRAPS above `Int64.max`, and a trap is uncatchable — it
        // kills the process rather than surfacing as a throw. `ObscuraClient` clamps the wire value
        // before it gets here, but this store is PUBLIC and the bind is the last line of defence.
        // `InboxStore.peek` saturates for the same reason at the other end of the same problem.
        //
        // Saturating at `Int64.max` deliberately does NOT resurrect the freeze this once caused: the
        // caller's clamp is what keeps `devices_updated_at` near now, and the LWW guard below is what
        // needs it. This only guarantees the bind cannot kill the app if a clamp is ever missed.
        let rawTs = timestamp ?? UInt64(Date().timeIntervalSince1970 * 1000)
        let ts = Int64(clamping: rawTs)
        let devicesJson = (try? JSONSerialization.data(withJSONObject: devices)).flatMap { String(data: $0, encoding: .utf8) } ?? "[]"

        try await db.write { db in
            // LWW: only update if newer
            try db.execute(sql: """
                UPDATE friends SET devices = ?, devices_updated_at = ?, updated_at = ?
                WHERE user_id = ? AND devices_updated_at < ?
            """, arguments: [devicesJson, ts, ts, userId, ts])
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
        let devicesJson: String = row["devices"]
        let devices: [[String: String]] = {
            guard let data = devicesJson.data(using: .utf8),
                  let parsed = try? JSONSerialization.jsonObject(with: data) as? [[String: String]]
            else { return [] }
            return parsed
        }()

        var friend = Friend(
            userId: row["user_id"],
            username: row["username"],
            status: FriendStatus(rawValue: row["status"]) ?? .pendingSent,
            devices: devices
        )
        friend.devicesUpdatedAt = UInt64(row["devices_updated_at"] as Int64)
        friend.createdAt = UInt64(row["created_at"] as Int64)
        friend.updatedAt = UInt64(row["updated_at"] as Int64)
        return friend
    }
}
