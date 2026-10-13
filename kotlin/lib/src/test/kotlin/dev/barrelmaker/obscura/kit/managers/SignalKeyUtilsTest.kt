package dev.barrelmaker.obscura.kit.managers

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.barrelmaker.obscura.kit.crypto.SignalStore
import dev.barrelmaker.obscura.kit.db.ObscuraDatabase
import dev.barrelmaker.obscura.kit.managers.SignalKeyUtils.toApiJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.Curve

class SignalKeyUtilsTest {

    private lateinit var store: SignalStore
    private lateinit var identity: IdentityKeyPair

    @BeforeEach
    fun setup() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ObscuraDatabase.Schema.create(driver)
        store = SignalStore(ObscuraDatabase(driver))
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
}
