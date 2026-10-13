import XCTest
import GRDB
import LibSignalClient
@testable import ObscuraKit

/// Unit tests for PersistentSignalStore — the GRDB-backed Signal Protocol store.
/// All tests use in-memory DB. No server, no network.
/// Proves the contract that Layer 2 encryption relies on.
final class PersistentSignalStoreTests: XCTestCase {

    // MARK: - Identity

    func testGenerateIdentityPersists() throws {
        let store = try PersistentSignalStore()
        XCTAssertFalse(store.hasPersistedIdentity)

        let (keyPair, regId) = store.generateIdentity()

        XCTAssertTrue(store.hasPersistedIdentity)
        let loaded = try store.identityKeyPair(context: NullContext())
        XCTAssertEqual(Array(loaded.serialize()), Array(keyPair.serialize()))
        let loadedRegId = try store.localRegistrationId(context: NullContext())
        XCTAssertEqual(loadedRegId, regId)
    }

    func testIdentitySurvivesReload() throws {
        // Use a file-backed DB to test persistence across init
        let dir = NSTemporaryDirectory() + "signal_store_test_\(UUID().uuidString)"
        try FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(atPath: dir) }

        let dbPath = (dir as NSString).appendingPathComponent("test.sqlite")
        let db = try DatabaseQueue(path: dbPath)
        try db.write { db in try db.execute(sql: "PRAGMA secure_delete = ON") }

        let store1 = try PersistentSignalStore(db: db)
        let (keyPair, regId) = store1.generateIdentity()

        // Create a new store from the same DB — simulates app restart
        let store2 = try PersistentSignalStore(db: db)
        XCTAssertTrue(store2.hasPersistedIdentity)
        let loaded = try store2.identityKeyPair(context: NullContext())
        XCTAssertEqual(Array(loaded.serialize()), Array(keyPair.serialize()))
        let loadedRegId = try store2.localRegistrationId(context: NullContext())
        XCTAssertEqual(loadedRegId, regId)
    }

    func testUninitializedIdentityThrows() throws {
        let store = try PersistentSignalStore()
        XCTAssertThrowsError(try store.identityKeyPair(context: NullContext()))
    }

    // MARK: - Identity trust (Signal's policy for unverified contacts)

    func testUnknownIdentityTrusted() throws {
        let store = try PersistentSignalStore()
        _ = store.generateIdentity()

        let key = IdentityKey(publicKey: PrivateKey.generate().publicKey)
        let addr = try ProtocolAddress(name: "alice", deviceId: 1)

        let trusted = try store.isTrustedIdentity(key, for: addr, direction: .receiving, context: NullContext())
        XCTAssertTrue(trusted, "First contact is trusted")
    }

    func testSameIdentityTrusted() throws {
        let store = try PersistentSignalStore()
        _ = store.generateIdentity()

        let key = IdentityKey(publicKey: PrivateKey.generate().publicKey)
        let addr = try ProtocolAddress(name: "alice", deviceId: 1)

        // Save identity
        _ = try store.saveIdentity(key, for: addr, context: NullContext())

        // Same key should be trusted
        let trusted = try store.isTrustedIdentity(key, for: addr, direction: .receiving, context: NullContext())
        XCTAssertTrue(trusted)
    }

    func testChangedIdentityIsTrustedAndReplacesTheStoredKey() throws {
        let store = try PersistentSignalStore()
        _ = store.generateIdentity()

        let key1 = IdentityKey(publicKey: PrivateKey.generate().publicKey)
        let key2 = IdentityKey(publicKey: PrivateKey.generate().publicKey)
        let addr = try ProtocolAddress(name: "alice", deviceId: 1)

        _ = try store.saveIdentity(key1, for: addr, context: NullContext())

        XCTAssertTrue(try store.isTrustedIdentity(key2, for: addr, direction: .sending, context: NullContext()))
        XCTAssertTrue(try store.isTrustedIdentity(key2, for: addr, direction: .receiving, context: NullContext()))
        XCTAssertTrue(try store.saveIdentity(key2, for: addr, context: NullContext()), "a changed key replaces the old one")
        XCTAssertEqual(try store.identity(for: addr, context: NullContext()), key2)
    }

    func testSaveIdentity_firstSaveReturnsNotReplaced() throws {
        let store = try PersistentSignalStore()
        _ = store.generateIdentity()

        let key = IdentityKey(publicKey: PrivateKey.generate().publicKey)
        let addr = try ProtocolAddress(name: "alice", deviceId: 1)

        let replaced = try store.saveIdentity(key, for: addr, context: NullContext())
        XCTAssertFalse(replaced, "First save should return false (no existing key)")
    }

    func testSaveIdentity_secondSaveReturnsReplaced() throws {
        let store = try PersistentSignalStore()
        _ = store.generateIdentity()

        let key1 = IdentityKey(publicKey: PrivateKey.generate().publicKey)
        let key2 = IdentityKey(publicKey: PrivateKey.generate().publicKey)
        let addr = try ProtocolAddress(name: "alice", deviceId: 1)

        _ = try store.saveIdentity(key1, for: addr, context: NullContext())
        let replaced = try store.saveIdentity(key2, for: addr, context: NullContext())
        XCTAssertTrue(replaced, "Second save should return true (key existed)")
    }

    func testIdentityLookup() throws {
        let store = try PersistentSignalStore()
        _ = store.generateIdentity()

        let key = IdentityKey(publicKey: PrivateKey.generate().publicKey)
        let addr = try ProtocolAddress(name: "alice", deviceId: 1)

        // No identity stored yet
        let missing = try store.identity(for: addr, context: NullContext())
        XCTAssertNil(missing)

        // Store and retrieve
        _ = try store.saveIdentity(key, for: addr, context: NullContext())
        let found = try store.identity(for: addr, context: NullContext())
        XCTAssertNotNil(found)
        XCTAssertEqual(Array(found!.serialize()), Array(key.serialize()))
    }

    // MARK: - PreKeys

    func testStoreAndLoadPreKey() throws {
        let store = try PersistentSignalStore()
        let preKey = PrivateKey.generate()
        let record = try PreKeyRecord(id: 42, publicKey: preKey.publicKey, privateKey: preKey)

        try store.storePreKey(record, id: 42, context: NullContext())
        let loaded = try store.loadPreKey(id: 42, context: NullContext())

        XCTAssertEqual(Array(loaded.serialize()), Array(record.serialize()))
    }

    func testRemovePreKey() throws {
        let store = try PersistentSignalStore()
        let preKey = PrivateKey.generate()
        let record = try PreKeyRecord(id: 7, publicKey: preKey.publicKey, privateKey: preKey)

        try store.storePreKey(record, id: 7, context: NullContext())
        try store.removePreKey(id: 7, context: NullContext())

        XCTAssertThrowsError(try store.loadPreKey(id: 7, context: NullContext()),
                             "Loading removed prekey should throw")
    }

    func testMissingPreKeyThrows() throws {
        let store = try PersistentSignalStore()
        XCTAssertThrowsError(try store.loadPreKey(id: 999, context: NullContext()),
                             "Loading nonexistent prekey should throw")
    }

    func testPreKeyCount() throws {
        let store = try PersistentSignalStore()
        XCTAssertEqual(store.getPreKeyCount(), 0)

        for i: UInt32 in 1...5 {
            let pk = PrivateKey.generate()
            try store.storePreKey(PreKeyRecord(id: i, publicKey: pk.publicKey, privateKey: pk), id: i, context: NullContext())
        }
        XCTAssertEqual(store.getPreKeyCount(), 5)

        try store.removePreKey(id: 3, context: NullContext())
        XCTAssertEqual(store.getPreKeyCount(), 4)
    }

    func testHighestPreKeyId() throws {
        let store = try PersistentSignalStore()
        XCTAssertEqual(store.getHighestPreKeyId(), 0)

        for i: UInt32 in [5, 10, 3] {
            let pk = PrivateKey.generate()
            try store.storePreKey(PreKeyRecord(id: i, publicKey: pk.publicKey, privateKey: pk), id: i, context: NullContext())
        }
        XCTAssertEqual(store.getHighestPreKeyId(), 10)
    }

    // MARK: - Signed PreKeys

    func testStoreAndLoadSignedPreKey() throws {
        let store = try PersistentSignalStore()
        _ = store.generateIdentity()
        let identity = try store.identityKeyPair(context: NullContext())

        let spk = PrivateKey.generate()
        let sig = identity.privateKey.generateSignature(message: spk.publicKey.serialize())
        let record = try SignedPreKeyRecord(id: 1, timestamp: UInt64(Date().timeIntervalSince1970), privateKey: spk, signature: sig)

        try store.storeSignedPreKey(record, id: 1, context: NullContext())
        let loaded = try store.loadSignedPreKey(id: 1, context: NullContext())

        XCTAssertEqual(Array(loaded.serialize()), Array(record.serialize()))
    }

    func testMissingSignedPreKeyThrows() throws {
        let store = try PersistentSignalStore()
        XCTAssertThrowsError(try store.loadSignedPreKey(id: 999, context: NullContext()),
                             "Loading nonexistent signed prekey should throw")
    }

    // MARK: - Sessions

    func testStoreAndLoadSession() throws {
        let store = try PersistentSignalStore()
        _ = store.generateIdentity()

        // We need a real session to serialize. Create one via processPreKeyBundle.
        let bobStore = try PersistentSignalStore()
        let (bobIdentity, bobRegId) = bobStore.generateIdentity()

        let bobPreKey = PrivateKey.generate()
        try bobStore.storePreKey(PreKeyRecord(id: 1, publicKey: bobPreKey.publicKey, privateKey: bobPreKey), id: 1, context: NullContext())
        let bobSpk = PrivateKey.generate()
        let bobSig = bobIdentity.privateKey.generateSignature(message: bobSpk.publicKey.serialize())
        try bobStore.storeSignedPreKey(SignedPreKeyRecord(id: 1, timestamp: UInt64(Date().timeIntervalSince1970), privateKey: bobSpk, signature: bobSig), id: 1, context: NullContext())

        let bundle = try PreKeyBundle(
            registrationId: bobRegId, deviceId: bobRegId,
            prekeyId: 1, prekey: bobPreKey.publicKey,
            signedPrekeyId: 1, signedPrekey: bobSpk.publicKey,
            signedPrekeySignature: Array(bobSig), identity: IdentityKey(publicKey: bobIdentity.publicKey)
        )
        let addr = try ProtocolAddress(name: "bob", deviceId: bobRegId)
        try processPreKeyBundle(bundle, for: addr, sessionStore: store, identityStore: store, context: NullContext())

        // Session should be loadable
        let session = try store.loadSession(for: addr, context: NullContext())
        XCTAssertNotNil(session)
    }

    func testMissingSessionReturnsNil() throws {
        let store = try PersistentSignalStore()
        let addr = try ProtocolAddress(name: "nobody", deviceId: 1)
        let session = try store.loadSession(for: addr, context: NullContext())
        XCTAssertNil(session, "Missing session should return nil, not throw")
    }

    func testDeleteAllSessionsClearsEveryPeer() throws {
        let store = try PersistentSignalStore()
        _ = store.generateIdentity()

        // Create sessions with two different "users" by establishing real sessions
        let bob1Store = try PersistentSignalStore()
        let (bob1Id, bob1Reg) = bob1Store.generateIdentity()
        let bob1PreKey = PrivateKey.generate()
        try bob1Store.storePreKey(PreKeyRecord(id: 1, publicKey: bob1PreKey.publicKey, privateKey: bob1PreKey), id: 1, context: NullContext())
        let bob1Spk = PrivateKey.generate()
        let bob1Sig = bob1Id.privateKey.generateSignature(message: bob1Spk.publicKey.serialize())
        try bob1Store.storeSignedPreKey(SignedPreKeyRecord(id: 1, timestamp: UInt64(Date().timeIntervalSince1970), privateKey: bob1Spk, signature: bob1Sig), id: 1, context: NullContext())

        let carolStore = try PersistentSignalStore()
        let (carolId, carolReg) = carolStore.generateIdentity()
        let carolPreKey = PrivateKey.generate()
        try carolStore.storePreKey(PreKeyRecord(id: 1, publicKey: carolPreKey.publicKey, privateKey: carolPreKey), id: 1, context: NullContext())
        let carolSpk = PrivateKey.generate()
        let carolSig = carolId.privateKey.generateSignature(message: carolSpk.publicKey.serialize())
        try carolStore.storeSignedPreKey(SignedPreKeyRecord(id: 1, timestamp: UInt64(Date().timeIntervalSince1970), privateKey: carolSpk, signature: carolSig), id: 1, context: NullContext())

        // Establish sessions
        let bobBundle = try PreKeyBundle(registrationId: bob1Reg, deviceId: bob1Reg, prekeyId: 1, prekey: bob1PreKey.publicKey, signedPrekeyId: 1, signedPrekey: bob1Spk.publicKey, signedPrekeySignature: Array(bob1Sig), identity: IdentityKey(publicKey: bob1Id.publicKey))
        let bobAddr = try ProtocolAddress(name: "bob", deviceId: bob1Reg)
        try processPreKeyBundle(bobBundle, for: bobAddr, sessionStore: store, identityStore: store, context: NullContext())

        let carolBundle = try PreKeyBundle(registrationId: carolReg, deviceId: carolReg, prekeyId: 1, prekey: carolPreKey.publicKey, signedPrekeyId: 1, signedPrekey: carolSpk.publicKey, signedPrekeySignature: Array(carolSig), identity: IdentityKey(publicKey: carolId.publicKey))
        let carolAddr = try ProtocolAddress(name: "carol", deviceId: carolReg)
        try processPreKeyBundle(carolBundle, for: carolAddr, sessionStore: store, identityStore: store, context: NullContext())

        // Both sessions exist
        XCTAssertNotNil(try store.loadSession(for: bobAddr, context: NullContext()))
        XCTAssertNotNil(try store.loadSession(for: carolAddr, context: NullContext()))

        // An identity change on this device invalidates every session.
        try store.deleteAllSessions()

        XCTAssertNil(try store.loadSession(for: bobAddr, context: NullContext()))
        XCTAssertNil(try store.loadSession(for: carolAddr, context: NullContext()))
    }

    // MARK: - Clear All

    func testClearAllWipesEverything() throws {
        let store = try PersistentSignalStore()
        let (identity, _) = store.generateIdentity()

        // Store some data in each table
        let pk = PrivateKey.generate()
        try store.storePreKey(PreKeyRecord(id: 1, publicKey: pk.publicKey, privateKey: pk), id: 1, context: NullContext())

        let spk = PrivateKey.generate()
        let sig = identity.privateKey.generateSignature(message: spk.publicKey.serialize())
        try store.storeSignedPreKey(SignedPreKeyRecord(id: 1, timestamp: UInt64(Date().timeIntervalSince1970), privateKey: spk, signature: sig), id: 1, context: NullContext())

        let remoteKey = IdentityKey(publicKey: PrivateKey.generate().publicKey)
        let addr = try ProtocolAddress(name: "alice", deviceId: 1)
        _ = try store.saveIdentity(remoteKey, for: addr, context: NullContext())

        // Clear everything
        store.clearAll()

        // Identity gone
        XCTAssertFalse(store.hasPersistedIdentity)
        XCTAssertThrowsError(try store.identityKeyPair(context: NullContext()))

        // PreKeys gone
        XCTAssertThrowsError(try store.loadPreKey(id: 1, context: NullContext()))
        XCTAssertEqual(store.getPreKeyCount(), 0)

        // Signed prekeys gone
        XCTAssertThrowsError(try store.loadSignedPreKey(id: 1, context: NullContext()))

        // Remote identities gone
        XCTAssertNil(try store.identity(for: addr, context: NullContext()))
    }
}
