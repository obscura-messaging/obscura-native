import XCTest
import GRDB
import LibSignalClient
@testable import ObscuraKit

final class PersistentSignalStoreTests: XCTestCase {
    private func store(_ db: DatabaseQueue? = nil) throws -> PersistentSignalStore {
        PersistentSignalStore(db: try db ?? ObscuraDatabase.inMemory(), logger: SilentLogger())
    }

    /// Installs fresh keys in `peer` and returns a bundle for them using its first one-time prekey.
    private func bundle(installingIn peer: PersistentSignalStore) throws -> PreKeyBundle {
        let keys = try SignalKeys.generate()
        try peer.install(keys)
        return try PreKeyBundle(
            registrationId: keys.registrationId, deviceId: 1, prekeyId: keys.preKeys[0].id, prekey: keys.preKeys[0].publicKey,
            signedPrekeyId: keys.signedPreKey.id, signedPrekey: keys.signedPreKey.publicKey,
            signedPrekeySignature: keys.signedPreKey.signature, identity: keys.identity.identityKey)
    }

    // MARK: - Identity

    func testInstallPersistsTheIdentityAndItsKeysAcrossStores() throws {
        let db = try ObscuraDatabase.inMemory()
        let first = try store(db)
        XCTAssertFalse(try first.hasIdentity())
        let keys = try SignalKeys.generate()

        try first.install(keys)

        let reopened = try store(db)
        XCTAssertTrue(try reopened.hasIdentity())
        XCTAssertEqual(try reopened.identityKeyPair(context: NullContext()).serialize(), keys.identity.serialize())
        XCTAssertEqual(try reopened.localRegistrationId(context: NullContext()), keys.registrationId)
        XCTAssertEqual(try reopened.preKeyCount(), Int(SignalKeys.initialPreKeyCount))
        XCTAssertEqual(try reopened.loadSignedPreKey(id: PersistentSignalStore.signedPreKeyId, context: NullContext()).serialize(),
                       keys.signedPreKey.serialize())
    }

    func testInstallReplacesEveryEarlierRecord() throws {
        let store = try store()
        try store.install(SignalKeys.generate())
        let peerAddress = try ProtocolAddress(name: "peer", deviceId: 1)
        try processPreKeyBundle(bundle(installingIn: try self.store()), for: peerAddress,
                                sessionStore: store, identityStore: store, context: NullContext())
        XCTAssertNotNil(try store.loadSession(for: peerAddress, context: NullContext()))

        let replacement = try SignalKeys.generate()
        try store.install(replacement)

        XCTAssertEqual(try store.identityKeyPair(context: NullContext()).serialize(), replacement.identity.serialize())
        XCTAssertNil(try store.loadSession(for: peerAddress, context: NullContext()))
        XCTAssertNil(try store.identity(for: peerAddress, context: NullContext()))
    }

    func testAMissingIdentityThrows() throws {
        let store = try store()
        XCTAssertThrowsError(try store.identityKeyPair(context: NullContext()))
        XCTAssertThrowsError(try store.localRegistrationId(context: NullContext()))
    }

    // MARK: - Identity trust (Signal's policy for unverified contacts)

    func testAChangedIdentityIsTrustedReplacedAndLogged() throws {
        let logger = RecordingLogger(SilentLogger())
        let store = PersistentSignalStore(db: try ObscuraDatabase.inMemory(), logger: logger)
        let first = IdentityKey(publicKey: PrivateKey.generate().publicKey)
        let second = IdentityKey(publicKey: PrivateKey.generate().publicKey)
        let address = try ProtocolAddress(name: "alice", deviceId: 1)

        XCTAssertTrue(try store.isTrustedIdentity(first, for: address, direction: .receiving, context: NullContext()))
        XCTAssertFalse(try store.saveIdentity(first, for: address, context: NullContext()), "first save replaces nothing")
        XCTAssertTrue(try store.saveIdentity(first, for: address, context: NullContext()), "a known key is replaced")
        XCTAssertTrue(logger.snapshot().isEmpty, "the same key again is not a change")
        XCTAssertTrue(try store.isTrustedIdentity(second, for: address, direction: .sending, context: NullContext()))
        XCTAssertTrue(try store.saveIdentity(second, for: address, context: NullContext()))
        XCTAssertEqual(try store.identity(for: address, context: NullContext()), second)
        XCTAssertEqual(logger.snapshot(), ["identity changed for alice.1"])
    }

    // MARK: - Prekeys

    func testRemovedAndMissingPreKeysThrowOnLoad() throws {
        let store = try store()
        try store.install(SignalKeys.generate())

        XCTAssertNoThrow(try store.loadPreKey(id: 7, context: NullContext()))
        try store.removePreKey(id: 7, context: NullContext())

        XCTAssertThrowsError(try store.loadPreKey(id: 7, context: NullContext()))
        XCTAssertThrowsError(try store.loadPreKey(id: 999, context: NullContext()))
        XCTAssertThrowsError(try store.loadSignedPreKey(id: 999, context: NullContext()))
        XCTAssertEqual(try store.preKeyCount(), 99)
    }

    func testAddedPreKeysFollowTheHighestIdAndUploadWithTheExistingSignedPreKey() throws {
        let store = try store()
        let keys = try SignalKeys.generate()
        try store.install(keys)

        let upload = try store.addPreKeys(50)

        XCTAssertEqual(upload.oneTimePreKeys.map(\.keyId), Array(101...150))
        XCTAssertEqual(try store.preKeyCount(), 150)
        XCTAssertEqual(upload.registrationId, Int(keys.registrationId))
        XCTAssertEqual(upload.signedPreKey.signature, Data(keys.signedPreKey.signature).base64EncodedString())
        XCTAssertEqual(upload.identityKey, Data(keys.identity.publicKey.serialize()).base64EncodedString())
    }

    // MARK: - Sessions

    func testAPreKeyMessageDecryptsAndItsSessionPersists() throws {
        let alice = try store()
        try alice.install(SignalKeys.generate())
        let bob = try store()
        let bobAddress = try ProtocolAddress(name: "bob", deviceId: 1)
        try processPreKeyBundle(bundle(installingIn: bob), for: bobAddress,
                                sessionStore: alice, identityStore: alice, context: NullContext())

        let cipher = try signalEncrypt(message: Array("test message".utf8), for: bobAddress,
                                       sessionStore: alice, identityStore: alice, context: NullContext())
        let decrypted = try signalDecryptPreKey(
            message: PreKeySignalMessage(bytes: cipher.serialize()), from: ProtocolAddress(name: "alice", deviceId: 1),
            sessionStore: bob, identityStore: bob, preKeyStore: bob, signedPreKeyStore: bob, kyberPreKeyStore: bob,
            context: NullContext())

        XCTAssertEqual(String(decoding: decrypted, as: UTF8.self), "test message")
        XCTAssertNotNil(try alice.loadSession(for: bobAddress, context: NullContext()))
        XCTAssertNil(try alice.loadSession(for: ProtocolAddress(name: "nobody", deviceId: 1), context: NullContext()))
    }
}
