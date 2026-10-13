package dev.barrelmaker.obscura.kit.messaging

import dev.barrelmaker.obscura.kit.crypto.fromBase64
import org.json.JSONArray
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.state.PreKeyBundle

/** One device's prekey bundle from `GET /v1/users/{userId}`, keyed by its device UUID. */
internal data class PeerBundle(val deviceId: String, val bundle: PreKeyBundle)

/** Parse the bundle response. Each bundle is bound to its own device UUID; there is no fallback. */
internal fun parsePeerBundles(json: JSONArray): List<PeerBundle> = (0 until json.length()).map { i ->
    val b = json.getJSONObject(i)
    val spk = b.getJSONObject("signedPreKey")
    val otp = b.optJSONObject("oneTimePreKey")
    PeerBundle(
        b.getString("deviceId"),
        PreKeyBundle(
            b.getInt("registrationId"), 1,
            otp?.getInt("keyId") ?: 0,
            otp?.let { Curve.decodePoint(it.getString("publicKey").fromBase64(), 0) },
            spk.getInt("keyId"),
            Curve.decodePoint(spk.getString("publicKey").fromBase64(), 0),
            spk.getString("signature").fromBase64(),
            IdentityKey(b.getString("identityKey").fromBase64()),
        ),
    )
}
