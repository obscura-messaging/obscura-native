package dev.barrelmaker.obscura.kit.managers

import dev.barrelmaker.obscura.kit.AuthState
import dev.barrelmaker.obscura.kit.LoginScenario
import dev.barrelmaker.obscura.kit.ObscuraError
import dev.barrelmaker.obscura.kit.ObscuraLogger
import dev.barrelmaker.obscura.kit.crypto.SignalStore
import dev.barrelmaker.obscura.kit.network.APIClient
import dev.barrelmaker.obscura.kit.network.Jwt
import dev.barrelmaker.obscura.kit.persistence.SessionStorage
import dev.barrelmaker.obscura.kit.stores.DeviceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class TokenStatus {
    FRESH,

    /** The server refused the refresh token. The session has ended. */
    REJECTED,

    /** The refresh could not complete (network or 5xx). The session is kept. */
    UNAVAILABLE,
}

internal data class Credentials(
    val token: String,
    val refreshToken: String,
    val userId: String,
    val deviceId: String,
    val username: String,
) {
    fun toMap(): Map<String, String> = mapOf(
        "token" to token, "refreshToken" to refreshToken, "userId" to userId, "deviceId" to deviceId, "username" to username,
    )

    companion object {
        fun from(map: Map<String, String>): Credentials? {
            val values = listOf("token", "refreshToken", "userId", "deviceId", "username").map { map[it] }
            if (values.any { it.isNullOrBlank() }) return null
            val (token, refreshToken, userId, deviceId, username) = values.map { it!! }
            return Credentials(token, refreshToken, userId, deviceId, username)
        }
    }
}

/**
 * The session: credentials, their persistence, and token refresh. The only writer of [state] and
 * of [SessionStorage].
 */
internal class AuthManager(
    private val api: APIClient,
    private val signalStore: SignalStore,
    private val devices: DeviceStore,
    private val storage: SessionStorage,
    private val deviceName: String,
    private val scope: CoroutineScope,
    private val logger: () -> ObscuraLogger,
    /** Ends the session after the server refused the refresh token. */
    private val onRejected: suspend () -> Unit,
) {
    private val _state = MutableStateFlow(AuthState.LOGGED_OUT)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    @Volatile var credentials: Credentials? = null
        private set

    private val lock = Any()
    private var refreshInFlight: Deferred<TokenStatus>? = null
    private var refreshLoop: Job? = null
    // Written only inside the single in-flight refresh.
    @Volatile private var refreshFailures = 0

    fun requireCredentials(): Credentials = credentials ?: throw ObscuraError.NotAuthenticated()

    suspend fun register(username: String, password: String) {
        provision(api.registerUser(username, password).token, username)
    }

    suspend fun login(username: String, password: String): LoginScenario {
        val localDeviceId = devices.getIdentity()
        if (localDeviceId != null) {
            try {
                val response = api.login(username, password, localDeviceId)
                // The server answers an unknown device id with a user-scoped token.
                val deviceId = response.deviceId ?: return LoginScenario.DEVICE_MISMATCH
                start(Credentials(response.token, response.refreshToken, Jwt.userId(response.token), deviceId, username))
                return LoginScenario.EXISTING_DEVICE
            } catch (e: ObscuraError.Http) {
                if (e.status !in REJECTION_STATUSES) throw e
            }
        }
        try {
            api.login(username, password, null)
        } catch (e: ObscuraError.Http) {
            if (e.status in REJECTION_STATUSES) return LoginScenario.INVALID_CREDENTIALS
            throw e
        }
        return if (localDeviceId != null) LoginScenario.DEVICE_MISMATCH else LoginScenario.NEW_DEVICE
    }

    suspend fun loginAndProvision(username: String, password: String) {
        provision(api.login(username, password, null).token, username)
    }

    /** Shared by [register] and [loginAndProvision]: new Signal keys, a new server device, a new session. */
    private suspend fun provision(userToken: String, username: String) {
        val response = api.provisionDevice(userToken, deviceName, SignalKeyUtils.generateDeviceKeys(signalStore))
        val deviceId = checkNotNull(response.deviceId) { "provisioning returned no device id" }
        devices.storeIdentity(deviceId)
        start(Credentials(response.token, response.refreshToken, Jwt.userId(response.token), deviceId, username))
    }

    /** Restore stored credentials. False when nothing usable is stored or the refresh token is refused. */
    suspend fun restore(): Boolean {
        val stored = storage.load()?.let(Credentials::from)
        if (stored == null) {
            storage.clear()
            return false
        }
        start(stored)
        return ensureFreshToken() != TokenStatus.REJECTED
    }

    /** Revoke the refresh token on the server. Best effort. */
    suspend fun revoke() {
        val current = credentials ?: return
        try {
            api.logout(current.refreshToken)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger().log("logout: refresh token not revoked: ${e.message}")
        }
    }

    fun end() {
        synchronized(lock) {
            refreshLoop?.cancel()
            refreshLoop = null
            credentials = null
        }
        storage.clear()
        _state.value = AuthState.LOGGED_OUT
    }

    suspend fun ensureFreshToken(): TokenStatus {
        val current = credentials ?: return TokenStatus.REJECTED
        if (secondsUntilExpiry(current.token) > FRESHNESS_MARGIN_S) return TokenStatus.FRESH
        return refresh()
    }

    /** Concurrent callers share one refresh: the refresh token is single-use. */
    suspend fun refresh(): TokenStatus = synchronized(lock) {
        refreshInFlight?.takeIf { it.isActive } ?: scope.async { performRefresh() }.also { refreshInFlight = it }
    }.await()

    private suspend fun performRefresh(): TokenStatus {
        val current = credentials ?: return TokenStatus.REJECTED
        return try {
            val response = api.refreshSession(current.refreshToken)
            val refreshed = current.copy(token = response.token, refreshToken = response.refreshToken)
            val stillCurrent = synchronized(lock) { (credentials == current).also { if (it) credentials = refreshed } }
            if (!stillCurrent) return if (credentials == null) TokenStatus.REJECTED else TokenStatus.FRESH
            storage.save(refreshed.toMap())
            refreshFailures = 0
            TokenStatus.FRESH
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger().tokenRefreshFailed(++refreshFailures, e.message ?: e.javaClass.simpleName)
            if (e is ObscuraError.Http && e.status in REJECTION_STATUSES) {
                if (credentials == current) onRejected()
                TokenStatus.REJECTED
            } else {
                TokenStatus.UNAVAILABLE
            }
        }
    }

    private fun start(new: Credentials) {
        storage.save(new.toMap())
        synchronized(lock) {
            credentials = new
            refreshFailures = 0
            refreshLoop?.cancel()
            refreshLoop = scope.launch { refreshAheadOfExpiry() }
        }
        _state.value = AuthState.AUTHENTICATED
    }

    private suspend fun refreshAheadOfExpiry() {
        while (true) {
            delay(if (refreshFailures == 0) refreshDelayMs() else retryDelayMs(refreshFailures))
            if (refresh() == TokenStatus.REJECTED) return
        }
    }

    /** 80% of the token's remaining lifetime, at least 5 s. */
    private fun refreshDelayMs(): Long {
        val token = credentials?.token ?: return MIN_REFRESH_DELAY_MS
        return (secondsUntilExpiry(token) * 800).coerceAtLeast(MIN_REFRESH_DELAY_MS)
    }

    private fun secondsUntilExpiry(token: String): Long =
        (Jwt.expiresAt(token) ?: 0L) - System.currentTimeMillis() / 1000

    private companion object {
        val REJECTION_STATUSES = setOf(401, 403)
        const val FRESHNESS_MARGIN_S = 60L
        const val MIN_REFRESH_DELAY_MS = 5_000L

        /** 5 s doubling to 5 min. */
        fun retryDelayMs(failures: Int): Long = (5_000L shl (failures - 1).coerceAtMost(6)).coerceAtMost(300_000L)
    }
}
