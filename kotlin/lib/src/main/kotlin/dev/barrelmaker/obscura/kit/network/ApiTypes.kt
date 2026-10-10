package dev.barrelmaker.obscura.kit.network

import org.json.JSONArray
import org.json.JSONObject

data class SignedPreKeyJson(val keyId: Int, val publicKey: String, val signature: String) {
    fun toJson() = JSONObject().apply {
        put("keyId", keyId)
        put("publicKey", publicKey)
        put("signature", signature)
    }
}

data class OneTimePreKeyJson(val keyId: Int, val publicKey: String) {
    fun toJson() = JSONObject().apply {
        put("keyId", keyId)
        put("publicKey", publicKey)
    }
}

fun List<OneTimePreKeyJson>.toJsonArray() = JSONArray(map { it.toJson() })

data class RegisterUserRequest(val username: String, val password: String) {
    fun toJson() = JSONObject().apply {
        put("username", username)
        put("password", password)
    }
}

data class LoginRequest(val username: String, val password: String, val deviceId: String? = null) {
    fun toJson() = JSONObject().apply {
        put("username", username)
        put("password", password)
        if (deviceId != null) put("deviceId", deviceId)
    }
}

data class RefreshTokenRequest(val refreshToken: String) {
    fun toJson() = JSONObject().apply {
        put("refreshToken", refreshToken)
    }
}

data class LogoutRequest(val refreshToken: String) {
    fun toJson() = JSONObject().apply {
        put("refreshToken", refreshToken)
    }
}

data class ProvisionDeviceRequest(
    val name: String,
    val identityKey: String,
    val registrationId: Int,
    val signedPreKey: SignedPreKeyJson,
    val oneTimePreKeys: List<OneTimePreKeyJson>
) {
    fun toJson() = JSONObject().apply {
        put("name", name)
        put("identityKey", identityKey)
        put("registrationId", registrationId)
        put("signedPreKey", signedPreKey.toJson())
        put("oneTimePreKeys", oneTimePreKeys.toJsonArray())
    }
}

data class UploadDeviceKeysRequest(
    val identityKey: String,
    val registrationId: Int,
    val signedPreKey: SignedPreKeyJson,
    val oneTimePreKeys: List<OneTimePreKeyJson>
) {
    fun toJson() = JSONObject().apply {
        put("identityKey", identityKey)
        put("registrationId", registrationId)
        put("signedPreKey", signedPreKey.toJson())
        put("oneTimePreKeys", oneTimePreKeys.toJsonArray())
    }
}

// ============================================================
// API response types
// ============================================================

data class AuthResponse(val token: String, val refreshToken: String?, val deviceId: String?)

enum class LoginScenario {
    EXISTING_DEVICE,     // Authenticated as the stored local device
    NEW_DEVICE,          // No local device; call loginAndProvision()
    DEVICE_MISMATCH,     // Server rejected the stored local device; wipeDevice() then loginAndProvision()
    INVALID_CREDENTIALS,
    USER_NOT_FOUND
}

data class ProvisionResponse(val token: String, val refreshToken: String?, val deviceId: String)
