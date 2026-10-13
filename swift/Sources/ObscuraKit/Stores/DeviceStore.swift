import Foundation
import GRDB

/// This device's identity plus every device the client can address, its own included.
actor DeviceStore {
    private let db: DatabaseQueue

    init(db: DatabaseQueue) {
        self.db = db
    }

    func localDeviceId() async throws -> String? {
        try await db.read { db in try String.fetchOne(db, sql: "SELECT device_id FROM device_identity WHERE id = 1") }
    }

    func setLocalDeviceId(_ deviceId: String) async throws {
        try await db.write { db in
            try db.execute(sql: "INSERT OR REPLACE INTO device_identity (id, device_id) VALUES (1, ?)", arguments: [deviceId])
        }
    }

    func deviceIds(of userId: String) async throws -> [String] {
        try await db.read { db in
            try String.fetchAll(db, sql: "SELECT device_id FROM devices WHERE user_id = ? ORDER BY device_id", arguments: [userId])
        }
    }

    func add(userId: String, deviceId: String) async throws {
        try await db.write { db in
            try db.execute(sql: "INSERT OR IGNORE INTO devices (user_id, device_id) VALUES (?, ?)", arguments: [userId, deviceId])
        }
    }

    func replace(userId: String, deviceIds: [String]) async throws {
        try await db.write { db in
            try db.execute(sql: "DELETE FROM devices WHERE user_id = ?", arguments: [userId])
            for deviceId in Set(deviceIds) {
                try db.execute(sql: "INSERT INTO devices (user_id, device_id) VALUES (?, ?)", arguments: [userId, deviceId])
            }
        }
    }
}
