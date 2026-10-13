import Foundation
import GRDB

public struct DeviceIdentity: Codable, Sendable, Equatable {
    public var deviceId: String

    public init(deviceId: String) {
        self.deviceId = deviceId
    }
}

/// One addressable device of `userId`. `name` is a display label and may be empty.
public struct Device: Codable, Sendable, Equatable {
    public var userId: String
    public var deviceId: String
    public var name: String

    public init(userId: String, deviceId: String, name: String = "") {
        self.userId = userId
        self.deviceId = deviceId
        self.name = name
    }
}

/// The device directory: this device's identity plus every device the client can address, its own
/// included. The only store of device lists; nothing else caches them.
public actor DeviceStore {
    private let db: DatabaseQueue

    public init(db: DatabaseQueue) throws {
        self.db = db
        try ObscuraSchema.migrate(db)
    }

    public init() throws {
        self.db = try DatabaseQueue()
        try db.write { db in try db.execute(sql: "PRAGMA secure_delete = ON") }
        try ObscuraSchema.migrate(db)
    }

    public func storeIdentity(_ identity: DeviceIdentity) async {
        try? await db.write { db in
            try db.execute(sql: """
                INSERT OR REPLACE INTO device_identity (id, device_id)
                VALUES (1, ?)
            """, arguments: [identity.deviceId])
        }
    }

    public func getIdentity() async -> DeviceIdentity? {
        try? await db.read { db -> DeviceIdentity? in
            guard let row = try Row.fetchOne(db, sql: "SELECT * FROM device_identity WHERE id = 1") else { return nil }
            return DeviceIdentity(deviceId: row["device_id"])
        }
    }

    public func devices(of userId: String) async throws -> [Device] {
        try await db.read { db in try Self.devices(of: userId, in: db) }
    }

    public func add(_ device: Device) async throws {
        try await db.write { db in
            try db.execute(sql: "INSERT OR REPLACE INTO devices (user_id, device_id, name) VALUES (?, ?, ?)",
                           arguments: [device.userId, device.deviceId, device.name])
        }
    }

    /// Replace `userId`'s devices with the server's current list. Known names are kept. A friend's
    /// list is stamped now, so an announcement older than this fetch cannot revert it.
    public func replaceFromServer(_ userId: String, deviceIds: [String]) async throws {
        try await db.write { db in
            let names = Dictionary(uniqueKeysWithValues: try Self.devices(of: userId, in: db).map { ($0.deviceId, $0.name) })
            try Self.write(userId, deviceIds.map { Device(userId: userId, deviceId: $0, name: names[$0] ?? "") }, in: db)
            _ = try Self.claimDevicesUpdate(userId, at: Int64(Date().timeIntervalSince1970 * 1000), in: db)
        }
    }

    /// Apply a friend's DEVICE_ANNOUNCE. Only an existing friend's list changes, and only when
    /// `timestamp` is newer than its last device-list write. Returns whether it applied.
    @discardableResult
    public func applyAnnounce(_ userId: String, devices: [Device], timestamp: UInt64) async throws -> Bool {
        // Saturating, not `Int64(_:)`, which traps above `Int64.max`. The caller clamps the wire
        // value first; this only guarantees the bind cannot kill the process.
        let stamp = Int64(clamping: timestamp)
        return try await db.write { db in
            guard try Self.claimDevicesUpdate(userId, at: stamp, in: db) else { return false }
            try Self.write(userId, devices, in: db)
            return true
        }
    }

    /// Replace `userId`'s devices with `devices` as given (link approval).
    public func replace(_ userId: String, devices: [Device]) async throws {
        try await db.write { db in try Self.write(userId, devices, in: db) }
    }

    public func clearAll() async {
        try? await db.write { db in
            try db.execute(sql: "DELETE FROM device_identity")
            try db.execute(sql: "DELETE FROM devices")
        }
    }

    private static func devices(of userId: String, in db: Database) throws -> [Device] {
        try Row.fetchAll(db, sql: "SELECT * FROM devices WHERE user_id = ? ORDER BY device_id", arguments: [userId])
            .map { Device(userId: $0["user_id"], deviceId: $0["device_id"], name: $0["name"]) }
    }

    private static func write(_ userId: String, _ devices: [Device], in db: Database) throws {
        precondition(devices.allSatisfy { $0.userId == userId }, "device list for \(userId) names another user")
        try db.execute(sql: "DELETE FROM devices WHERE user_id = ?", arguments: [userId])
        var seen = Set<String>()
        for device in devices where seen.insert(device.deviceId).inserted {
            try db.execute(sql: "INSERT INTO devices (user_id, device_id, name) VALUES (?, ?, ?)",
                           arguments: [userId, device.deviceId, device.name])
        }
    }

    /// Claims the device-list write for `userId` at `stamp`; true only if the friend exists and
    /// `stamp` is newer than its last device-list write.
    private static func claimDevicesUpdate(_ userId: String, at stamp: Int64, in db: Database) throws -> Bool {
        try db.execute(sql: """
            UPDATE friends SET devices_updated_at = ?, updated_at = ?
            WHERE user_id = ? AND devices_updated_at < ?
        """, arguments: [stamp, stamp, userId, stamp])
        return db.changesCount == 1
    }
}
