package dev.barrelmaker.obscura.kit.crypto

import dev.barrelmaker.obscura.kit.db.ObscuraDatabase
import org.signal.libsignal.protocol.*
import org.signal.libsignal.protocol.util.KeyHelper
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.groups.state.SenderKeyStore
import org.signal.libsignal.protocol.state.*
import java.util.UUID

/**
 * SQLDelight-backed implementation of all Signal protocol store interfaces.
 * This is the Kotlin equivalent of IndexedDBStore.js.
 */
class SignalStore(
    private val db: ObscuraDatabase
) : SignalProtocolStore {

    private var identityKeyPair: IdentityKeyPair? = null
    private var localRegistrationId: Int = 0

    /** Callback when a contact's identity key changes (safety number changed). */
    var onIdentityChanged: ((address: String, oldKey: ByteArray?, newKey: ByteArray) -> Unit)? = null

    init {
        // Restore local identity from SQLite if it exists
        val saved = db.signalKeyQueries.selectLocalIdentity().executeAsOneOrNull()
        if (saved != null) {
            identityKeyPair = IdentityKeyPair(saved.identity_key_pair)
            localRegistrationId = saved.registration_id.toInt()
        }
    }

    /**
     * Initialize with an existing identity or generate a new one.
     */
    fun initialize(keyPair: IdentityKeyPair, registrationId: Int) {
        this.identityKeyPair = keyPair
        this.localRegistrationId = registrationId
        db.signalKeyQueries.insertLocalIdentity(keyPair.serialize(), registrationId.toLong())
    }

    /**
     * Generate fresh identity and registration ID.
     */
    fun generateIdentity(): Pair<IdentityKeyPair, Int> {
        val keyPair = IdentityKeyPair.generate()
        val registrationId = KeyHelper.generateRegistrationId(false)
        initialize(keyPair, registrationId)
        return Pair(keyPair, registrationId)
    }

    override fun getIdentityKeyPair(): IdentityKeyPair {
        return identityKeyPair ?: throw IllegalStateException("Identity not initialized")
    }

    override fun getLocalRegistrationId(): Int {
        return localRegistrationId
    }

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val addressStr = "${address.name}.${address.deviceId}"
        val existing = db.signalKeyQueries.selectIdentityByAddress(addressStr).executeAsOneOrNull()
        db.signalKeyQueries.insertIdentity(addressStr, identityKey.serialize())
        val replaced = existing != null && !java.security.MessageDigest.isEqual(existing, identityKey.serialize())
        if (replaced) {
            onIdentityChanged?.invoke(addressStr, existing!!, identityKey.serialize())
        }
        return if (replaced) IdentityKeyStore.IdentityChange.REPLACED_EXISTING else IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
    }

    /**
     * Signal's policy for an unverified contact: a changed identity key (reinstall, takeover) is
     * accepted, not refused. [saveIdentity] records the new key and reports the change through
     * [onIdentityChanged], so the app can show that the safety number changed. Refusing it would
     * leave the peer permanently unreachable, with no way to recover.
     */
    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction
    ): Boolean = true

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? {
        val addressStr = "${address.name}.${address.deviceId}"
        val stored = db.signalKeyQueries.selectIdentityByAddress(addressStr).executeAsOneOrNull()
            ?: return null
        return IdentityKey(stored)
    }

    override fun loadPreKey(preKeyId: Int): PreKeyRecord {
        val record = db.signalKeyQueries.selectPreKey(preKeyId.toLong()).executeAsOneOrNull()
            ?: throw InvalidKeyIdException("No prekey with id: $preKeyId")
        return PreKeyRecord(record)
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        db.signalKeyQueries.insertPreKey(preKeyId.toLong(), record.serialize())
    }

    override fun containsPreKey(preKeyId: Int): Boolean {
        return db.signalKeyQueries.selectPreKey(preKeyId.toLong()).executeAsOneOrNull() != null
    }

    override fun removePreKey(preKeyId: Int) {
        db.signalKeyQueries.deletePreKey(preKeyId.toLong())
    }

    fun getPreKeyCount(): Long {
        return db.signalKeyQueries.countPreKeys().executeAsOne()
    }

    fun getHighestPreKeyId(): Long {
        return db.signalKeyQueries.highestPreKeyId().executeAsOne().MAX ?: 0L
    }

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
        val record = db.signalKeyQueries.selectSignedPreKey(signedPreKeyId.toLong()).executeAsOneOrNull()
            ?: throw InvalidKeyIdException("No signed prekey with id: $signedPreKeyId")
        return SignedPreKeyRecord(record)
    }

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> {
        // Not frequently needed; return empty for now
        return emptyList()
    }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        db.signalKeyQueries.insertSignedPreKey(signedPreKeyId.toLong(), record.serialize())
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean {
        return db.signalKeyQueries.selectSignedPreKey(signedPreKeyId.toLong()).executeAsOneOrNull() != null
    }

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        db.signalKeyQueries.deleteSignedPreKey(signedPreKeyId.toLong())
    }

    override fun loadSession(address: SignalProtocolAddress): SessionRecord {
        val addressStr = "${address.name}.${address.deviceId}"
        val record = db.signalKeyQueries.selectSession(addressStr).executeAsOneOrNull()
            ?: return SessionRecord()
        return SessionRecord(record)
    }

    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> {
        return addresses.map { address ->
            val addressStr = "${address.name}.${address.deviceId}"
            val record = db.signalKeyQueries.selectSession(addressStr).executeAsOneOrNull()
                ?: throw NoSessionException("No session for: $addressStr")
            SessionRecord(record)
        }
    }

    // Messenger pins every address to `<deviceUuid>.1`, so this kit has no sub-device
    // sessions. libsignal still requires the method.
    override fun getSubDeviceSessions(name: String): List<Int> = emptyList()

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        val addressStr = "${address.name}.${address.deviceId}"
        db.signalKeyQueries.insertSession(addressStr, record.serialize())
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean {
        val addressStr = "${address.name}.${address.deviceId}"
        return db.signalKeyQueries.selectSession(addressStr).executeAsOneOrNull() != null
    }

    override fun deleteSession(address: SignalProtocolAddress) {
        val addressStr = "${address.name}.${address.deviceId}"
        db.signalKeyQueries.deleteSession(addressStr)
    }

    /** Every session with every peer. Used when this device's own identity changes. */
    fun deleteAllSessions() {
        db.signalKeyQueries.deleteAllSessions()
    }

    override fun deleteAllSessions(name: String) {
        val allSessions = db.signalKeyQueries.selectAllSessions().executeAsList()
        allSessions
            .filter { it.address.startsWith("$name.") }
            .forEach { db.signalKeyQueries.deleteSession(it.address) }
    }

    override fun storeSenderKey(
        sender: SignalProtocolAddress,
        distributionId: UUID,
        record: SenderKeyRecord
    ) {
        val key = "${sender.name}.${sender.deviceId}::$distributionId"
        db.signalKeyQueries.insertSenderKey(key, record.serialize())
    }

    override fun loadSenderKey(
        sender: SignalProtocolAddress,
        distributionId: UUID
    ): SenderKeyRecord? {
        val key = "${sender.name}.${sender.deviceId}::$distributionId"
        val record = db.signalKeyQueries.selectSenderKey(key).executeAsOneOrNull()
            ?: return null
        return SenderKeyRecord(record)
    }

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord {
        throw InvalidKeyIdException("Kyber not implemented")
    }

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> {
        return emptyList()
    }

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        // Not implemented — Kyber is post-quantum, not yet used
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean {
        return false
    }

    override fun markKyberPreKeyUsed(kyberPreKeyId: Int) {
        // Not implemented
    }
}
