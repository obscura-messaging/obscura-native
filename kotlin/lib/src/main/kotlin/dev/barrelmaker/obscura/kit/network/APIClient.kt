package dev.barrelmaker.obscura.kit.network

import dev.barrelmaker.obscura.kit.ObscuraError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

/** The REST API. Authenticated calls use [token]; a missing token is [ObscuraError.NotAuthenticated]. */
internal class APIClient(baseUrl: String, private val token: () -> String?) {
    private val baseUrl = baseUrl.trimEnd('/')

    private val http = OkHttpClient.Builder()
        // ObscuraConfig allows http:// only for loopback.
        .connectionSpecs(
            if (baseUrl.startsWith("http://")) listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT)
            else listOf(ConnectionSpec.MODERN_TLS)
        )
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun registerUser(username: String, password: String): AuthResponse = AuthResponse.from(
        json("POST", "/v1/users", JSONObject().put("username", username).put("password", password), bearer = null)
    )

    suspend fun login(username: String, password: String, deviceId: String?): AuthResponse {
        val body = JSONObject().put("username", username).put("password", password)
        if (deviceId != null) body.put("deviceId", deviceId)
        return AuthResponse.from(json("POST", "/v1/sessions", body, bearer = null))
    }

    /** Creates a device for the user that [userToken] belongs to. */
    suspend fun provisionDevice(userToken: String, name: String, keys: DeviceKeys): AuthResponse =
        AuthResponse.from(json("POST", "/v1/devices", keys.toJson().put("name", name), bearer = userToken))

    suspend fun refreshSession(refreshToken: String): AuthResponse = AuthResponse.from(
        json("POST", "/v1/sessions/refresh", JSONObject().put("refreshToken", refreshToken), bearer = null)
    )

    suspend fun logout(refreshToken: String, bearer: String) {
        send("DELETE", "/v1/sessions", JSONObject().put("refreshToken", refreshToken).body(), bearer)
    }

    suspend fun registerPushToken(pushToken: String) {
        send("PUT", "/v1/push-tokens", JSONObject().put("token", pushToken).body(), requireToken())
    }

    suspend fun uploadDeviceKeys(keys: DeviceKeys) {
        send("POST", "/v1/devices/keys", keys.toJson().body(), requireToken())
    }

    suspend fun fetchPreKeyBundles(userId: String): JSONArray =
        JSONArray(String(send("GET", "/v1/users/${enc(userId)}", null, requireToken())))

    suspend fun fetchOwnDeviceIds(): List<String> {
        val bytes = send("GET", "/v1/devices", null, requireToken())
        try {
            val response = JSONObject(String(bytes))
            val devices = response.getJSONArray("devices")
            return (0 until devices.length()).map { devices.getJSONObject(it).getString("deviceId") }.also { ids ->
                if (ids.any { runCatching { UUID.fromString(it) }.isFailure } || ids.distinct().size != ids.size) {
                    throw ObscuraError.ProtocolViolation("Invalid own-device directory")
                }
            }
        } catch (e: org.json.JSONException) {
            throw ObscuraError.ProtocolViolation("Invalid own-device directory", e)
        }
    }

    /** The idempotency key is derived from the body, so a retried batch cannot be delivered twice. */
    suspend fun sendMessage(protobuf: ByteArray): ByteArray = send(
        "POST", "/v1/messages", protobuf.toRequestBody(PROTOBUF), requireToken(),
        "Idempotency-Key" to UUID.nameUUIDFromBytes(protobuf).toString(),
    )

    suspend fun uploadAttachment(blob: ByteArray): String =
        JSONObject(String(send("POST", "/v1/attachments", blob.toRequestBody(OCTET), requireToken()))).getString("id")

    suspend fun fetchAttachment(id: String): ByteArray = send("GET", "/v1/attachments/${enc(id)}", null, requireToken())

    suspend fun fetchGatewayTicket(): String = json("POST", "/v1/gateway/ticket", JSONObject(), requireToken()).getString("ticket")

    fun gatewayUrl(ticket: String): String =
        baseUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/v1/gateway?ticket=${enc(ticket)}"

    private fun requireToken(): String = token() ?: throw ObscuraError.NotAuthenticated()

    private suspend fun json(method: String, path: String, body: JSONObject, bearer: String?): JSONObject {
        val bytes = send(method, path, body.body(), bearer)
        return try {
            JSONObject(String(bytes))
        } catch (e: org.json.JSONException) {
            throw ObscuraError.ProtocolViolation("Invalid JSON response for $path", e)
        }
    }

    /** Retries 429 and 503 after `Retry-After`; any other non-2xx is [ObscuraError.Http]. */
    private suspend fun send(
        method: String,
        path: String,
        body: RequestBody?,
        bearer: String?,
        vararg headers: Pair<String, String>,
    ): ByteArray {
        val request = Request.Builder().url("$baseUrl$path").method(method, body).apply {
            if (bearer != null) header("Authorization", "Bearer $bearer")
            headers.forEach { (name, value) -> header(name, value) }
        }.build()
        var retries = 0
        while (true) {
            val (status, bytes, retryAfter) = withContext(Dispatchers.IO) {
                http.newCall(request).execute().use { Triple(it.code, it.body.bytes(), it.header("Retry-After")) }
            }
            if (status in 200..299) return bytes
            if (status !in RETRYABLE || retries == MAX_RETRIES) throw ObscuraError.Http(status, String(bytes))
            retries++
            delay(retryAfterMs(retryAfter))
        }
    }

    private fun JSONObject.body() = toString().toRequestBody(JSON)

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        private val JSON = "application/json".toMediaType()
        private val PROTOBUF = "application/x-protobuf".toMediaType()
        private val OCTET = "application/octet-stream".toMediaType()
        private val RETRYABLE = setOf(429, 503)
        private const val MAX_RETRIES = 2

        /** `Retry-After` seconds clamped to [1, 10]; the server sends 0 for a sub-second wait. */
        fun retryAfterMs(header: String?): Long = (header?.trim()?.toLongOrNull() ?: 1L).coerceIn(1L, 10L) * 1000
    }
}
