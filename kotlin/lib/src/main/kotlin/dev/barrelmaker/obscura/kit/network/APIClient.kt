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

    /** Returns a user-scoped token. */
    suspend fun registerUser(username: String, password: String): String = AuthResponse.from(
        json("POST", "/v1/users", credentials(username, password), bearer = null)
    ).token

    /** Device-scoped when [deviceId] belongs to the user, otherwise user-scoped. */
    suspend fun login(username: String, password: String, deviceId: String?): AuthResponse {
        val body = credentials(username, password)
        if (deviceId != null) body.put("deviceId", deviceId)
        return AuthResponse.from(json("POST", "/v1/sessions", body, bearer = null))
    }

    suspend fun resetAccount(username: String, password: String) {
        send("POST", "/v1/account/reset", credentials(username, password).body(), bearer = null)
    }

    /** Creates a device for the user that [userToken] belongs to; [linkSessionId] when the account has devices. */
    suspend fun provisionDevice(userToken: String, name: String, keys: DeviceKeys, linkSessionId: String?): AuthResponse {
        val body = keys.toJson().put("name", name)
        if (linkSessionId != null) body.put("linkSessionId", linkSessionId)
        return AuthResponse.from(json("POST", "/v1/devices", body, bearer = userToken))
    }

    suspend fun refreshSession(refreshToken: String): AuthResponse = AuthResponse.from(
        json("POST", "/v1/sessions/refresh", JSONObject().put("refreshToken", refreshToken), bearer = null)
    )

    suspend fun logout(refreshToken: String, bearer: String) {
        send("DELETE", "/v1/sessions", JSONObject().put("refreshToken", refreshToken).body(), bearer)
    }

    suspend fun registerPushToken(pushToken: String) {
        send("PUT", "/v1/push-tokens", JSONObject().put("token", pushToken).body(), requireToken())
    }

    suspend fun uploadPreKeys(keys: PreKeyUpload) {
        send("POST", "/v1/devices/keys", keys.toJson().body(), requireToken())
    }

    /** One bundle per device of [userId]; consumes a one-time prekey on each. */
    suspend fun fetchPreKeyBundles(userId: String): JSONArray =
        parse("/v1/users") { JSONArray(String(send("GET", "/v1/users/${enc(userId)}", null, requireToken()))) }

    /** The bundle of one device; consumes a one-time prekey on that device only. */
    suspend fun fetchPreKeyBundle(userId: String, deviceId: String): JSONObject = parse("/v1/users") {
        JSONObject(String(send("GET", "/v1/users/${enc(userId)}/devices/${enc(deviceId)}", null, requireToken())))
    }

    /** The account's devices. [bearer] may be a user-scoped token. */
    suspend fun fetchOwnDevices(bearer: String = requireToken()): List<OwnDevice> = parse("/v1/devices") {
        val devices = JSONObject(String(send("GET", "/v1/devices", null, bearer))).getJSONArray("devices")
        (0 until devices.length()).map { i ->
            val d = devices.getJSONObject(i)
            OwnDevice(d.getString("deviceId"), if (d.isNull("name")) null else d.getString("name"), d.optBoolean("isPrimary"))
        }
    }.also { list ->
        if (list.any { !isUuid(it.deviceId) } || list.map { it.deviceId }.distinct().size != list.size || list.count { it.isPrimary } > 1) {
            throw ObscuraError.ProtocolViolation("Invalid device list")
        }
    }

    suspend fun deleteDevice(deviceId: String) {
        send("DELETE", "/v1/devices/${enc(deviceId)}", null, requireToken())
    }

    suspend fun createLinkSession(userToken: String): LinkSession = json("POST", "/v1/link-sessions", JSONObject(), userToken).let {
        val id = it.optString("id")
        val expiresAt = it.optLong("expiresAt")
        if (id.isBlank() || expiresAt <= 0) throw ObscuraError.ProtocolViolation("Invalid link session")
        LinkSession(id, expiresAt)
    }

    suspend fun approveLinkSession(id: String, body: ByteArray) {
        send("PUT", "/v1/link-sessions/${enc(id)}", body.toRequestBody(OCTET), requireToken())
    }

    /** The approved body, or null when the session was not approved within [waitSeconds]. */
    suspend fun awaitLinkSession(userToken: String, id: String, waitSeconds: Int): ByteArray? {
        val (status, bytes) = exchange("GET", "/v1/link-sessions/${enc(id)}?wait=$waitSeconds", null, userToken,
            readTimeoutSeconds = waitSeconds + 15L)
        return if (status == 204) null else bytes
    }

    /** A fresh idempotency key per call; [send]'s own 429/503 retries reuse it. */
    suspend fun sendMessage(protobuf: ByteArray): ByteArray = send(
        "POST", "/v1/messages", protobuf.toRequestBody(PROTOBUF), requireToken(),
        "Idempotency-Key" to UUID.randomUUID().toString(),
    )

    suspend fun uploadAttachment(blob: ByteArray): String =
        JSONObject(String(send("POST", "/v1/attachments", blob.toRequestBody(OCTET), requireToken()))).getString("id")

    suspend fun fetchAttachment(id: String): ByteArray = send("GET", "/v1/attachments/${enc(id)}", null, requireToken())

    suspend fun fetchGatewayTicket(): String = json("POST", "/v1/gateway/ticket", JSONObject(), requireToken()).getString("ticket")

    fun gatewayUrl(ticket: String): String =
        baseUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/v1/gateway?ticket=${enc(ticket)}"

    private fun requireToken(): String = token() ?: throw ObscuraError.NotAuthenticated()

    private suspend fun json(method: String, path: String, body: JSONObject, bearer: String?): JSONObject =
        parse(path) { JSONObject(String(send(method, path, body.body(), bearer))) }

    private inline fun <T> parse(path: String, block: () -> T): T = try {
        block()
    } catch (e: org.json.JSONException) {
        throw ObscuraError.ProtocolViolation("Invalid JSON response for $path", e)
    }

    private fun credentials(username: String, password: String) = JSONObject().put("username", username).put("password", password)

    private suspend fun send(
        method: String,
        path: String,
        body: RequestBody?,
        bearer: String?,
        vararg headers: Pair<String, String>,
    ): ByteArray = exchange(method, path, body, bearer, *headers).second

    /** Retries 429 and 503 after `Retry-After`; any other non-2xx is [ObscuraError.Http]. */
    private suspend fun exchange(
        method: String,
        path: String,
        body: RequestBody?,
        bearer: String?,
        vararg headers: Pair<String, String>,
        readTimeoutSeconds: Long? = null,
    ): Pair<Int, ByteArray> {
        val request = Request.Builder().url("$baseUrl$path").method(method, body).apply {
            if (bearer != null) header("Authorization", "Bearer $bearer")
            headers.forEach { (name, value) -> header(name, value) }
        }.build()
        val client = readTimeoutSeconds?.let { http.newBuilder().readTimeout(it, TimeUnit.SECONDS).build() } ?: http
        var retries = 0
        while (true) {
            val (status, bytes, retryAfter) = withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { Triple(it.code, it.body.bytes(), it.header("Retry-After")) }
            }
            if (status in 200..299) return status to bytes
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
