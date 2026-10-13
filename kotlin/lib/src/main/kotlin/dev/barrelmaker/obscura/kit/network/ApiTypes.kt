package dev.barrelmaker.obscura.kit.network

import dev.barrelmaker.obscura.kit.crypto.fromBase64Url
import dev.barrelmaker.obscura.kit.ObscuraError
import org.json.JSONArray
import org.json.JSONObject

internal data class SignedPreKeyJson(val keyId: Int, val publicKey: String, val signature: String) {
    fun toJson(): JSONObject = JSONObject().put("keyId", keyId).put("publicKey", publicKey).put("signature", signature)
}

internal data class OneTimePreKeyJson(val keyId: Int, val publicKey: String) {
    fun toJson(): JSONObject = JSONObject().put("keyId", keyId).put("publicKey", publicKey)
}

/** The key material a device publishes, at provisioning and on every prekey upload. */
internal data class DeviceKeys(
    val identityKey: String,
    val registrationId: Int,
    val signedPreKey: SignedPreKeyJson,
    val oneTimePreKeys: List<OneTimePreKeyJson>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("identityKey", identityKey)
        .put("registrationId", registrationId)
        .put("signedPreKey", signedPreKey.toJson())
        .put("oneTimePreKeys", JSONArray(oneTimePreKeys.map { it.toJson() }))
}

/** [deviceId] is present only for a device-scoped token. */
internal data class AuthResponse(val token: String, val refreshToken: String, val deviceId: String?) {
    companion object {
        fun from(json: JSONObject): AuthResponse {
            val token = json.optString("token")
            val refreshToken = json.optString("refreshToken")
            if (token.isBlank() || refreshToken.isBlank()) throw ObscuraError.ProtocolViolation("Missing session tokens")
            return AuthResponse(token, refreshToken, json.optString("deviceId").ifBlank { null })
        }
    }
}

/** The claims this kit reads from an access token. It never verifies the signature; the server does. */
internal object Jwt {
    fun userId(token: String): String = claims(token)?.optString("sub")?.takeIf { it.isNotBlank() }
        ?: throw ObscuraError.ProtocolViolation("Access token has no subject")

    /** Expiry in epoch seconds, or null when the token has none or cannot be read. */
    fun expiresAt(token: String): Long? = claims(token)?.optLong("exp")?.takeIf { it > 0 }

    private fun claims(token: String): JSONObject? =
        runCatching { JSONObject(String(token.split(".")[1].fromBase64Url())) }.getOrNull()
}
