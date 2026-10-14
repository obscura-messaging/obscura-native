package dev.barrelmaker.obscura.kit.crypto

import dev.barrelmaker.obscura.kit.db.ObscuraDatabase
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.KeyHelper
import java.security.MessageDigest
import java.util.UUID

/** All libsignal store interfaces over SQLite. The server has no Kyber prekeys. */
internal class SignalStore(private val db: ObscuraDatabase) : SignalProtocolStore {
    private val queries = db.signalKeyQueries

    @Volatile private var localIdentity: Pair<IdentityKeyPair, Int>? =
        queries.selectLocalIdentity().executeAsOneOrNull()?.let { IdentityKeyPair(it.identity_key_pair) to it.registration_id.toInt() }

    /** Called with the address whose identity key changed (a peer reinstalled or re-keyed). */
    var onIdentityChanged: (String) -> Unit = {}

    fun hasIdentity(): Boolean = localIdentity != null && queries.selectLocalIdentity().executeAsOneOrNull() != null

    /** Keep ratchet changes and the corresponding durable receive writes in the same transaction. */
    internal fun <T> transaction(block: () -> T): T = db.transactionWithResult { block() }

    fun generateIdentity(): Pair<IdentityKeyPair, Int> {
        val identity = IdentityKeyPair.generate() to KeyHelper.generateRegistrationId(false)
        queries.insertLocalIdentity(identity.first.serialize(), identity.second.toLong())
        localIdentity = identity
        return identity
    }

    /** Delete every key, session and identity, this device's included. */
    fun clear() {
        db.transaction {
            queries.deleteLocalIdentity()
            queries.deleteAllIdentities()
            queries.deleteAllPreKeys()
            queries.deleteAllSignedPreKeys()
            queries.deleteAllSessions()
            queries.deleteAllSenderKeys()
        }
        localIdentity = null
    }

    override fun getIdentityKeyPair(): IdentityKeyPair = checkNotNull(localIdentity) { "Identity not initialized" }.first
    override fun getLocalRegistrationId(): Int = checkNotNull(localIdentity) { "Identity not initialized" }.second

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val key = address.key()
        val existing = queries.selectIdentityByAddress(key).executeAsOneOrNull()
        queries.insertIdentity(key, identityKey.serialize())
        if (existing == null || MessageDigest.isEqual(existing, identityKey.serialize())) {
            return IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        }
        onIdentityChanged(key)
        return IdentityKeyStore.IdentityChange.REPLACED_EXISTING
    }

    // An unverified contact's changed identity is accepted and reported; refusing it would leave the
    // peer unreachable with no way to recover.
    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean = true

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? =
        queries.selectIdentityByAddress(address.key()).executeAsOneOrNull()?.let(::IdentityKey)

    override fun loadPreKey(preKeyId: Int): PreKeyRecord =
        queries.selectPreKey(preKeyId.toLong()).executeAsOneOrNull()?.let(::PreKeyRecord)
            ?: throw InvalidKeyIdException("No prekey with id: $preKeyId")

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) = queries.insertPreKey(preKeyId.toLong(), record.serialize())
    override fun containsPreKey(preKeyId: Int): Boolean = queries.selectPreKey(preKeyId.toLong()).executeAsOneOrNull() != null
    override fun removePreKey(preKeyId: Int) = queries.deletePreKey(preKeyId.toLong())

    fun getPreKeyCount(): Long = queries.countPreKeys().executeAsOne()
    fun getHighestPreKeyId(): Long = queries.highestPreKeyId().executeAsOne().MAX ?: 0L

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord =
        queries.selectSignedPreKey(signedPreKeyId.toLong()).executeAsOneOrNull()?.let(::SignedPreKeyRecord)
            ?: throw InvalidKeyIdException("No signed prekey with id: $signedPreKeyId")

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> =
        queries.selectAllSignedPreKeys().executeAsList().map(::SignedPreKeyRecord)

    fun getHighestSignedPreKeyId(): Long = queries.highestSignedPreKeyId().executeAsOne().MAX ?: 0L

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) =
        queries.insertSignedPreKey(signedPreKeyId.toLong(), record.serialize())

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean =
        queries.selectSignedPreKey(signedPreKeyId.toLong()).executeAsOneOrNull() != null

    override fun removeSignedPreKey(signedPreKeyId: Int) = queries.deleteSignedPreKey(signedPreKeyId.toLong())

    override fun loadSession(address: SignalProtocolAddress): SessionRecord =
        queries.selectSession(address.key()).executeAsOneOrNull()?.let(::SessionRecord) ?: SessionRecord()

    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> = addresses.map {
        queries.selectSession(it.key()).executeAsOneOrNull()?.let(::SessionRecord)
            ?: throw NoSessionException("No session for: ${it.key()}")
    }

    // Every address is `<deviceUuid>.1`, so there are no sub-device sessions.
    override fun getSubDeviceSessions(name: String): List<Int> = emptyList()

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) =
        queries.insertSession(address.key(), record.serialize())

    override fun containsSession(address: SignalProtocolAddress): Boolean =
        queries.selectSession(address.key()).executeAsOneOrNull() != null

    override fun deleteSession(address: SignalProtocolAddress) = queries.deleteSession(address.key())

    fun deleteAllSessions() = queries.deleteAllSessions()

    override fun deleteAllSessions(name: String) = queries.deleteSessionsByPrefix("$name.%")

    override fun storeSenderKey(sender: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) =
        queries.insertSenderKey("${sender.key()}::$distributionId", record.serialize())

    override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? =
        queries.selectSenderKey("${sender.key()}::$distributionId").executeAsOneOrNull()?.let(::SenderKeyRecord)

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord = throw InvalidKeyIdException("No Kyber prekeys")
    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> = emptyList()
    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) = throw UnsupportedOperationException("No Kyber prekeys")
    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean = false
    override fun markKyberPreKeyUsed(kyberPreKeyId: Int) = throw UnsupportedOperationException("No Kyber prekeys")

    private fun SignalProtocolAddress.key() = "$name.$deviceId"
}
