package dev.barrelmaker.obscura.kit.managers

import dev.barrelmaker.obscura.kit.crypto.SignalStore
import dev.barrelmaker.obscura.kit.crypto.toBase64
import dev.barrelmaker.obscura.kit.network.DeviceKeys
import dev.barrelmaker.obscura.kit.network.OneTimePreKeyJson
import dev.barrelmaker.obscura.kit.network.SignedPreKeyJson
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

internal object SignalKeyUtils {
    const val SIGNED_PREKEY_ID = 1
    private const val INITIAL_PREKEY_COUNT = 100

    /** A new identity, signed prekey and one-time prekeys, stored locally, as a device publishes them. */
    fun generateDeviceKeys(store: SignalStore): DeviceKeys {
        val (identity, registrationId) = store.generateIdentity()
        val signedPreKey = generateSignedPreKey(store, identity, SIGNED_PREKEY_ID)
        return DeviceKeys(
            identityKey = identity.publicKey.serialize().toBase64(),
            registrationId = registrationId,
            signedPreKey = signedPreKey.toApiJson(),
            oneTimePreKeys = generateOneTimePreKeys(store, 1, INITIAL_PREKEY_COUNT).toApiJson(),
        )
    }

    /** Refill with the current identity and a fresh signed prekey; earlier signed keys remain stored. */
    fun generateMorePreKeys(store: SignalStore, count: Int): DeviceKeys = DeviceKeys(
        identityKey = store.identityKeyPair.publicKey.serialize().toBase64(),
        registrationId = store.localRegistrationId,
        signedPreKey = generateSignedPreKey(
            store, store.identityKeyPair, Math.toIntExact(store.getHighestSignedPreKeyId() + 1),
        ).toApiJson(),
        oneTimePreKeys = generateOneTimePreKeys(store, store.getHighestPreKeyId().toInt() + 1, count).toApiJson(),
    )

    fun generateSignedPreKey(store: SignalStore, identity: IdentityKeyPair, id: Int): SignedPreKeyRecord {
        val keyPair = Curve.generateKeyPair()
        val signature = Curve.calculateSignature(identity.privateKey, keyPair.publicKey.serialize())
        return SignedPreKeyRecord(id, System.currentTimeMillis(), keyPair, signature).also { store.storeSignedPreKey(id, it) }
    }

    fun generateOneTimePreKeys(store: SignalStore, startId: Int, count: Int): List<PreKeyRecord> =
        (startId until startId + count).map { id -> PreKeyRecord(id, Curve.generateKeyPair()).also { store.storePreKey(id, it) } }

    fun SignedPreKeyRecord.toApiJson() =
        SignedPreKeyJson(id, keyPair.publicKey.serialize().toBase64(), signature.toBase64())

    fun List<PreKeyRecord>.toApiJson() = map { OneTimePreKeyJson(it.id, it.keyPair.publicKey.serialize().toBase64()) }
}
