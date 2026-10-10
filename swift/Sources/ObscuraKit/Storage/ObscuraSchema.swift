import GRDB

/// The current Obscura database schema.
///
/// There is no released schema to migrate. Pre-release installs must clear app
/// data when this schema changes; future releases add migrations from this
/// baseline rather than carrying retired prototype tables forward.
public enum ObscuraSchema {
    public static var migrator: DatabaseMigrator {
        var migrator = DatabaseMigrator()
        migrator.eraseDatabaseOnSchemaChange = false

        migrator.registerMigration("v1") { db in
            try db.execute(sql: """
                CREATE TABLE friends (
                    user_id TEXT PRIMARY KEY,
                    username TEXT NOT NULL,
                    status TEXT NOT NULL,
                    devices TEXT NOT NULL DEFAULT '[]',
                    devices_updated_at INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
            """)

            try db.execute(sql: """
                CREATE TABLE device_identity (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    device_id TEXT NOT NULL
                )
            """)
            try db.execute(sql: """
                CREATE TABLE own_devices (
                    device_id TEXT PRIMARY KEY,
                    device_name TEXT NOT NULL
                )
            """)

            try db.execute(sql: """
                CREATE TABLE signal_local_identity (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    key_pair BLOB NOT NULL,
                    registration_id INTEGER NOT NULL
                )
            """)
            try db.execute(sql: """
                CREATE TABLE signal_identities (
                    address TEXT PRIMARY KEY,
                    key_data BLOB NOT NULL
                )
            """)
            try db.execute(sql: """
                CREATE TABLE signal_prekeys (
                    key_id INTEGER PRIMARY KEY,
                    record BLOB NOT NULL
                )
            """)
            try db.execute(sql: """
                CREATE TABLE signal_signed_prekeys (
                    key_id INTEGER PRIMARY KEY,
                    record BLOB NOT NULL
                )
            """)
            try db.execute(sql: """
                CREATE TABLE signal_sessions (
                    address TEXT PRIMARY KEY,
                    record BLOB NOT NULL
                )
            """)
            try db.execute(sql: """
                CREATE TABLE signal_sender_keys (
                    key_id TEXT PRIMARY KEY,
                    record BLOB NOT NULL
                )
            """)

            try db.execute(sql: """
                CREATE TABLE inbox_rows (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    envelope_id TEXT NOT NULL UNIQUE,
                    kind TEXT NOT NULL,
                    sender_user_id TEXT NOT NULL,
                    sender_device_id TEXT,
                    model_key TEXT,
                    entry_id TEXT,
                    sent_at INTEGER,
                    payload BLOB NOT NULL
                )
            """)
            try db.execute(sql: "CREATE INDEX idx_inbox_id ON inbox_rows(id)")

            try db.execute(sql: """
                CREATE TABLE model_entries (
                    model_name TEXT NOT NULL,
                    id TEXT NOT NULL,
                    data TEXT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    author_device_id TEXT NOT NULL,
                    local_metadata TEXT,
                    PRIMARY KEY (model_name, id)
                )
            """)
        }

        return migrator
    }

    public static func migrate(_ writer: DatabaseQueue) throws {
        try migrator.migrate(writer)
    }

    public static let expectedTables: Set<String> = [
        "friends",
        "device_identity",
        "own_devices",
        "signal_local_identity",
        "signal_identities",
        "signal_prekeys",
        "signal_signed_prekeys",
        "signal_sessions",
        "signal_sender_keys",
        "inbox_rows",
        "model_entries",
    ]
}
