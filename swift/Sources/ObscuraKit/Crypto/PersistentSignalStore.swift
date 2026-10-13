import Foundation
import GRDB
import LibSignalClient

/// GRDB-backed Signal Protocol store — persistent across app restarts.
/// Implements all 6 libsignal store interfaces synchronously.
/// Matches Kotlin's SignalStore.kt backed by SQLDelight.
public class PersistentSignalStore: IdentityKeyStore, PreKeyStore, SignedPreKeyStore, SessionStore, SenderKeyStore, KyberPreKeyStore {

    private let db: DatabaseQueue
    private var _identityKeyPair: IdentityKeyPair?
    private var _registrationId: UInt32 = 0
    public var logger: ObscuraLogger = PrintLogger()

    public init(db: DatabaseQueue) throws {
        self.db = db
        try createTables()
    }

    /// In-memory database for testing
    public init() throws {
        self.db = try DatabaseQueue()
        try db.write { db in try db.execute(sql: "PRAGMA secure_delete = ON") }
        try createTables()
    }

    private func createTables() throws {
        try ObscuraSchema.migrate(db)

        // Restore persisted identity if available
        if let (keyPair, regId) = try loadPersistedIdentity() {
            _identityKeyPair = keyPair
            _registrationId = regId
        }
    }

    // MARK: - Identity Management

    /// Initialize with a keypair and persist to DB.
    public func initialize(keyPair: IdentityKeyPair, registrationId: UInt32) {
        _identityKeyPair = keyPair
        _registrationId = registrationId
        try? db.write { db in
            try db.execute(sql: """
                INSERT OR REPLACE INTO signal_local_identity (id, key_pair, registration_id)
                VALUES (1, ?, ?)
            """, arguments: [Data(keyPair.serialize()), registrationId])
        }
    }

    /// True if this store has a persisted identity (survives restart).
    public var hasPersistedIdentity: Bool {
        _identityKeyPair != nil
    }

    private func loadPersistedIdentity() throws -> (IdentityKeyPair, UInt32)? {
        try db.read { db -> (IdentityKeyPair, UInt32)? in
            guard let row = try Row.fetchOne(db, sql: "SELECT key_pair, registration_id FROM signal_local_identity WHERE id = 1") else {
                return nil
            }
            let keyPairData: Data = row["key_pair"]
            let regId = UInt32(row["registration_id"] as Int64)
            let keyPair = try IdentityKeyPair(bytes: Array(keyPairData))
            return (keyPair, regId)
        }
    }

    public func generateIdentity() -> (IdentityKeyPair, UInt32) {
        let keyPair = IdentityKeyPair.generate()
        let regId = UInt32.random(in: 1...16380)
        initialize(keyPair: keyPair, registrationId: regId)
        return (keyPair, regId)
    }

    // MARK: - IdentityKeyStore

    public func identityKeyPair(context: StoreContext) throws -> IdentityKeyPair {
        guard let kp = _identityKeyPair else { throw SignalError.invalidState("Identity not initialized") }
        return kp
    }

    public func localRegistrationId(context: StoreContext) throws -> UInt32 {
        return _registrationId
    }

    public func saveIdentity(_ identity: IdentityKey, for address: ProtocolAddress, context: StoreContext) throws -> Bool {
        let addressStr = "\(address.name).\(address.deviceId)"
        let newKeyData = Data(identity.serialize())
        let existing: Data? = try db.read { db in
            try Data.fetchOne(db, sql: "SELECT key_data FROM signal_identities WHERE address = ?", arguments: [addressStr])
        }
        // Detect identity key change — potential MITM
        if let existing = existing, !constantTimeEqual(existing, newKeyData) {
            logger.identityChanged(address: addressStr)
        }
        try db.write { db in
            try db.execute(sql: "INSERT OR REPLACE INTO signal_identities (address, key_data) VALUES (?, ?)",
                           arguments: [addressStr, newKeyData])
        }
        return existing != nil
    }

    /// Signal's policy for an unverified contact: a changed identity key (reinstall, takeover) is
    /// accepted, not refused. `saveIdentity` records the new key and logs the change as a security
    /// event, so the app can show that the safety number changed. Refusing it would leave the peer
    /// permanently unreachable, with no way to recover.
    public func isTrustedIdentity(_ identity: IdentityKey, for address: ProtocolAddress, direction: Direction, context: StoreContext) throws -> Bool {
        true
    }

    public func identity(for address: ProtocolAddress, context: StoreContext) throws -> IdentityKey? {
        let addressStr = "\(address.name).\(address.deviceId)"
        let stored: Data? = try db.read { db in
            try Data.fetchOne(db, sql: "SELECT key_data FROM signal_identities WHERE address = ?", arguments: [addressStr])
        }
        guard let data = stored else { return nil }
        return try IdentityKey(bytes: Array(data))
    }

    // MARK: - PreKeyStore

    public func loadPreKey(id: UInt32, context: StoreContext) throws -> PreKeyRecord {
        guard let data: Data = try db.read({ db in
            try Data.fetchOne(db, sql: "SELECT record FROM signal_prekeys WHERE key_id = ?", arguments: [id])
        }) else {
            throw SignalError.invalidKeyIdentifier("no prekey with this identifier")
        }
        return try PreKeyRecord(bytes: Array(data))
    }

    public func storePreKey(_ record: PreKeyRecord, id: UInt32, context: StoreContext) throws {
        try db.write { db in
            try db.execute(sql: "INSERT OR REPLACE INTO signal_prekeys (key_id, record) VALUES (?, ?)",
                           arguments: [id, Data(record.serialize())])
        }
    }

    public func getPreKeyCount() -> Int {
        (try? db.read { db in
            try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM signal_prekeys") ?? 0
        }) ?? 0
    }

    public func getHighestPreKeyId() -> UInt32 {
        let val64 = (try? db.read { db in
            try Int64.fetchOne(db, sql: "SELECT MAX(key_id) FROM signal_prekeys")
        }) ?? nil
        return UInt32(val64 ?? 0)
    }

    public func removePreKey(id: UInt32, context: StoreContext) throws {
        try db.write { db in
            try db.execute(sql: "DELETE FROM signal_prekeys WHERE key_id = ?", arguments: [id])
        }
    }

    // MARK: - SignedPreKeyStore

    public func loadSignedPreKey(id: UInt32, context: StoreContext) throws -> SignedPreKeyRecord {
        guard let data: Data = try db.read({ db in
            try Data.fetchOne(db, sql: "SELECT record FROM signal_signed_prekeys WHERE key_id = ?", arguments: [id])
        }) else {
            throw SignalError.invalidKeyIdentifier("no signed prekey with this identifier")
        }
        return try SignedPreKeyRecord(bytes: Array(data))
    }

    public func storeSignedPreKey(_ record: SignedPreKeyRecord, id: UInt32, context: StoreContext) throws {
        try db.write { db in
            try db.execute(sql: "INSERT OR REPLACE INTO signal_signed_prekeys (key_id, record) VALUES (?, ?)",
                           arguments: [id, Data(record.serialize())])
        }
    }

    // MARK: - SessionStore

    public func loadSession(for address: ProtocolAddress, context: StoreContext) throws -> SessionRecord? {
        let addressStr = "\(address.name).\(address.deviceId)"
        guard let data: Data = try db.read({ db in
            try Data.fetchOne(db, sql: "SELECT record FROM signal_sessions WHERE address = ?", arguments: [addressStr])
        }) else {
            return nil
        }
        return try SessionRecord(bytes: Array(data))
    }

    public func loadExistingSessions(for addresses: [ProtocolAddress], context: StoreContext) throws -> [SessionRecord] {
        try addresses.compactMap { try loadSession(for: $0, context: context) }
    }

    public func storeSession(_ record: SessionRecord, for address: ProtocolAddress, context: StoreContext) throws {
        let addressStr = "\(address.name).\(address.deviceId)"
        try db.write { db in
            try db.execute(sql: "INSERT OR REPLACE INTO signal_sessions (address, record) VALUES (?, ?)",
                           arguments: [addressStr, Data(record.serialize())])
        }
    }

    /// Every session with every peer. Used when this device's own identity changes.
    public func deleteAllSessions() throws {
        try db.write { db in try db.execute(sql: "DELETE FROM signal_sessions") }
    }

    // MARK: - SenderKeyStore

    public func storeSenderKey(from sender: ProtocolAddress, distributionId: UUID, record: SenderKeyRecord, context: StoreContext) throws {
        let key = "\(sender.name).\(sender.deviceId)::\(distributionId)"
        try db.write { db in
            try db.execute(sql: "INSERT OR REPLACE INTO signal_sender_keys (key_id, record) VALUES (?, ?)",
                           arguments: [key, Data(record.serialize())])
        }
    }

    public func loadSenderKey(from sender: ProtocolAddress, distributionId: UUID, context: StoreContext) throws -> SenderKeyRecord? {
        let key = "\(sender.name).\(sender.deviceId)::\(distributionId)"
        guard let data: Data = try db.read({ db in
            try Data.fetchOne(db, sql: "SELECT record FROM signal_sender_keys WHERE key_id = ?", arguments: [key])
        }) else {
            return nil
        }
        return try SenderKeyRecord(bytes: Array(data))
    }

    // MARK: - KyberPreKeyStore (not implemented — server doesn't use Kyber)

    public func loadKyberPreKey(id: UInt32, context: StoreContext) throws -> KyberPreKeyRecord {
        throw SignalError.invalidKeyIdentifier("Kyber not implemented")
    }

    public func storeKyberPreKey(_ record: KyberPreKeyRecord, id: UInt32, context: StoreContext) throws {
        // Not implemented
    }

    public func markKyberPreKeyUsed(id: UInt32, context: StoreContext) throws {
        // Not implemented
    }

    // MARK: - Clear All

    /// Wipe all Signal state (logout). Identity, sessions, keys — everything.
    public func clearAll() {
        _identityKeyPair = nil
        _registrationId = 0
        try? db.write { db in
            try db.execute(sql: "DELETE FROM signal_local_identity")
            try db.execute(sql: "DELETE FROM signal_identities")
            try db.execute(sql: "DELETE FROM signal_prekeys")
            try db.execute(sql: "DELETE FROM signal_signed_prekeys")
            try db.execute(sql: "DELETE FROM signal_sessions")
            try db.execute(sql: "DELETE FROM signal_sender_keys")
        }
    }
}
