package dev.barrelmaker.obscura.kit.network

import dev.barrelmaker.obscura.kit.crypto.fromBase64Url
import dev.barrelmaker.obscura.kit.ObscuraError
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class SignedPreKeyJson(val keyId: Int, val publicKey: String, val signature: String) {
    fun toJson(): JSONObject = JSONObject().put("keyId", keyId).put("publicKey", publicKey).put("signature", signature)
}

internal data class OneTimePreKeyJson(val keyId: Int, val publicKey: String) {
    fun toJson(): JSONObject = JSONObject().put("keyId", keyId).put("publicKey", publicKey)
}

/** The prekeys a device publishes on every refill. The identity key cannot change there. */
internal data class PreKeyUpload(val signedPreKey: SignedPreKeyJson, val oneTimePreKeys: List<OneTimePreKeyJson>) {
    fun toJson(): JSONObject = JSONObject()
        .put("signedPreKey", signedPreKey.toJson())
        .put("oneTimePreKeys", JSONArray(oneTimePreKeys.map { it.toJson() }))
}

/** The key material a device publishes when it is created. */
internal data class DeviceKeys(val identityKey: String, val registrationId: Int, val preKeys: PreKeyUpload) {
    fun toJson(): JSONObject = preKeys.toJson()
        .put("identityKey", identityKey)
        .put("registrationId", registrationId)
}

/** [refreshToken] and [deviceId] are present only for a device-scoped token. */
internal data class AuthResponse(val token: String, val refreshToken: String?, val deviceId: String?) {
    companion object {
        fun from(json: JSONObject): AuthResponse {
            val token = json.optString("token")
            if (token.isBlank()) throw ObscuraError.ProtocolViolation("Missing session token")
            return AuthResponse(token, json.optString("refreshToken").ifBlank { null }, json.optString("deviceId").ifBlank { null })
        }
    }
}

/** One device of the signed-in account, from `GET /v1/devices`. */
internal data class OwnDevice(val deviceId: String, val name: String?, val isPrimary: Boolean)

internal data class LinkSession(val id: String, val expiresAt: Long)

/** The claims this kit reads from an access token. It never verifies the signature; the server does. */
internal object Jwt {
    fun userId(token: String): String = claims(token)?.optString("sub")?.takeIf { it.isNotBlank() }
        ?: throw ObscuraError.ProtocolViolation("Access token has no subject")

    /** Expiry in epoch seconds, or null when the token has none or cannot be read. */
    fun expiresAt(token: String): Long? = claims(token)?.optLong("exp")?.takeIf { it > 0 }

    private fun claims(token: String): JSONObject? =
        runCatching { JSONObject(String(token.split(".")[1].fromBase64Url())) }.getOrNull()
}

internal fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value.lowercase() }.getOrDefault(false)
