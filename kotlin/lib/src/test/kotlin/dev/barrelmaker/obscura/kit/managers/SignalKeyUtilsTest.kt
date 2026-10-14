package dev.barrelmaker.obscura.kit.managers

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.barrelmaker.obscura.kit.crypto.SignalStore
import dev.barrelmaker.obscura.kit.db.ObscuraDatabase
import dev.barrelmaker.obscura.kit.managers.SignalKeyUtils.toApiJson
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle

class SignalKeyUtilsTest {

    private lateinit var store: SignalStore
    private lateinit var db: ObscuraDatabase
    private lateinit var identity: IdentityKeyPair

    @BeforeEach
    fun setup() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ObscuraDatabase.Schema.create(driver)
        db = ObscuraDatabase(driver)
        store = SignalStore(db)
        identity = store.generateIdentity().first
    }

    @Test
    fun `generateSignedPreKey produces a signature that verifies against the identity key`() {
        val record = SignalKeyUtils.generateSignedPreKey(store, identity, id = 7)

        assertEquals(7, record.id)
        assertTrue(
            Curve.verifySignature(
                identity.publicKey.publicKey,
                record.keyPair.publicKey.serialize(),
                record.signature
            ),
            "signed prekey signature must verify against the identity public key"
        )
    }

    @Test
    fun `generateSignedPreKey persists the record in the store`() {
        val record = SignalKeyUtils.generateSignedPreKey(store, identity, id = 3)
        val loaded = store.loadSignedPreKey(3)
        assertEquals(record.id, loaded.id)
    }

    @Test
    fun `generateOneTimePreKeys creates a contiguous id range and stores each`() {
        val keys = SignalKeyUtils.generateOneTimePreKeys(store, startId = 100, count = 5)

        assertEquals(listOf(100, 101, 102, 103, 104), keys.map { it.id })
        for (id in 100..104) assertTrue(store.containsPreKey(id), "prekey $id should be stored")
        assertFalse(store.containsPreKey(105))
    }

    @Test
    fun `generateOneTimePreKeys with zero count produces none`() {
        assertTrue(SignalKeyUtils.generateOneTimePreKeys(store, startId = 1, count = 0).isEmpty())
    }

    @Test
    fun `signed prekey toApiJson carries the id and base64 key material`() {
        val record = SignalKeyUtils.generateSignedPreKey(store, identity, id = 9)
        val json = record.toApiJson()

        assertEquals(9, json.keyId)
        assertTrue(json.publicKey.isNotEmpty())
        assertTrue(json.signature.isNotEmpty())
    }

    @Test
    fun `one-time prekey list toApiJson maps ids and keys in order`() {
        val keys = SignalKeyUtils.generateOneTimePreKeys(store, startId = 50, count = 3)
        val json = keys.toApiJson()

        assertEquals(listOf(50, 51, 52), json.map { it.keyId })
        assertTrue(json.all { it.publicKey.isNotEmpty() })
    }

    @Test
    fun `every refill advances the signed prekey id required by the server across reloads`() {
        val initial = SignalKeyUtils.generateDeviceKeys(store)
        var serverMaxId = initial.signedPreKey.keyId
        val records = mutableMapOf(serverMaxId to store.loadSignedPreKey(serverMaxId).serialize())

        repeat(3) { refill ->
            store = SignalStore(db)
            val upload = SignalKeyUtils.generateMorePreKeys(store, 50)

            assertTrue(upload.signedPreKey.keyId > serverMaxId, "the server rejects an equal or lower signed prekey id")
            assertEquals(serverMaxId + 1, upload.signedPreKey.keyId)
            assertEquals(initial.identityKey, upload.identityKey)
            assertEquals(initial.registrationId, upload.registrationId)
            assertEquals((101 + refill * 50..150 + refill * 50).toList(), upload.oneTimePreKeys.map { it.keyId })
            val signed = store.loadSignedPreKey(upload.signedPreKey.keyId)
            assertEquals(signed.toApiJson(), upload.signedPreKey)
            assertTrue(Curve.verifySignature(store.identityKeyPair.publicKey.publicKey, signed.keyPair.publicKey.serialize(), signed.signature))
            records.forEach { (id, bytes) -> assertArrayEquals(bytes, store.loadSignedPreKey(id).serialize()) }
            serverMaxId = upload.signedPreKey.keyId
            records[serverMaxId] = signed.serialize()
        }
    }

    @Test
    fun `a refill after an unpublished attempt does not reuse its signed prekey id`() {
        SignalKeyUtils.generateDeviceKeys(store)
        val unpublished = SignalKeyUtils.generateMorePreKeys(store, 50)
        store = SignalStore(db)

        val retry = SignalKeyUtils.generateMorePreKeys(store, 50)

        assertEquals(3, retry.signedPreKey.keyId)
        assertTrue(retry.signedPreKey.keyId > unpublished.signedPreKey.keyId)
        assertEquals((151..200).toList(), retry.oneTimePreKeys.map { it.keyId })
        assertTrue(store.containsSignedPreKey(unpublished.signedPreKey.keyId))
    }

    @Test
    fun `a message using an earlier bundle still decrypts after signed prekey rotation`() {
        SignalKeyUtils.generateDeviceKeys(store)
        val signed = store.loadSignedPreKey(1)
        val preKey = store.loadPreKey(1)
        val senderDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        senderDriver.use { driver ->
            ObscuraDatabase.Schema.create(driver)
            val sender = SignalStore(ObscuraDatabase(driver)).also { it.generateIdentity() }
            val receiverAddress = SignalProtocolAddress("receiver", 1)
            SessionBuilder(sender, receiverAddress).process(PreKeyBundle(
                store.localRegistrationId, 1, preKey.id, preKey.keyPair.publicKey,
                signed.id, signed.keyPair.publicKey, signed.signature, store.identityKeyPair.publicKey,
            ))
            val plaintext = "delayed message".toByteArray()
            val ciphertext = SessionCipher(sender, receiverAddress).encrypt(plaintext)

            repeat(2) { SignalKeyUtils.generateMorePreKeys(store, 50) }
            store = SignalStore(db)

            assertArrayEquals(plaintext, SessionCipher(store, SignalProtocolAddress("sender", 1))
                .decrypt(PreKeySignalMessage(ciphertext.serialize())))
        }
    }
}
