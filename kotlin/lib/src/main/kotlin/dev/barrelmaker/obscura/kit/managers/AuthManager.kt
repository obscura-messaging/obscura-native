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
            if (runCatching { Jwt.userId(token) }.getOrNull() != userId || Jwt.expiresAt(token) == null) return null
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
    private val onRejected: suspend (Credentials, Long) -> Unit,
) {
    private val _state = MutableStateFlow(AuthState.LOGGED_OUT)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    @Volatile var credentials: Credentials? = null
        private set

    private val lock = Any()
    private var refreshInFlight: Deferred<TokenStatus>? = null
    private var refreshLoop: Job? = null
    private var generation = 0L
    // Written only inside the single in-flight refresh.
    @Volatile private var refreshFailures = 0

    fun requireCredentials(): Credentials = credentials ?: throw ObscuraError.NotAuthenticated()

    suspend fun register(username: String, password: String) {
        validateLogin(username, password)
        requireUnprovisioned()
        provision(api.registerUser(username, password).token, username)
    }

    suspend fun login(username: String, password: String): LoginScenario {
        validateLogin(username, password)
        val localDeviceId = devices.getIdentity()
        if (localDeviceId != null) {
            try {
                val response = api.login(username, password, localDeviceId)
                // The server answers an unknown device id with a user-scoped token.
                val deviceId = response.deviceId ?: return LoginScenario.DEVICE_MISMATCH
                if (deviceId != localDeviceId || !signalStore.hasIdentity()) return LoginScenario.DEVICE_MISMATCH
                start(Credentials(response.token, response.refreshToken, Jwt.userId(response.token), deviceId, username))
                return LoginScenario.EXISTING_DEVICE
            } catch (e: ObscuraError.Http) {
                if (e.status !in REJECTION_STATUSES) throw e
                return LoginScenario.INVALID_CREDENTIALS
            }
        }
        try {
            api.login(username, password, null)
        } catch (e: ObscuraError.Http) {
            if (e.status in REJECTION_STATUSES) return LoginScenario.INVALID_CREDENTIALS
            throw e
        }
        return LoginScenario.NEW_DEVICE
    }

    suspend fun loginAndProvision(username: String, password: String) {
        validateLogin(username, password)
        requireUnprovisioned()
        provision(api.login(username, password, null).token, username)
    }

    /** Shared by [register] and [loginAndProvision]: new Signal keys, a new server device, a new session. */
    private suspend fun provision(userToken: String, username: String) {
        val response = api.provisionDevice(userToken, deviceName, SignalKeyUtils.generateDeviceKeys(signalStore))
        val deviceId = response.deviceId ?: throw ObscuraError.ProtocolViolation("Provisioning returned no device id")
        if (runCatching { java.util.UUID.fromString(deviceId) }.isFailure) {
            throw ObscuraError.ProtocolViolation("Provisioning returned an invalid device id")
        }
        val credentials = Credentials(response.token, response.refreshToken, Jwt.userId(response.token), deviceId, username)
        devices.storeIdentity(deviceId)
        start(credentials)
    }

    /** Restore stored credentials. False when nothing usable is stored or the refresh token is refused. */
    suspend fun restore(): Boolean {
        val stored = synchronized(lock) { stored("load") { storage.load() } }?.let(Credentials::from)
        if (stored == null || !signalStore.hasIdentity() || devices.getIdentity() != stored.deviceId) {
            synchronized(lock) { stored("clear") { storage.clear() } }
            return false
        }
        start(stored)
        return ensureFreshToken() != TokenStatus.REJECTED
    }

    /** Revoke the refresh token on the server. Best effort. */
    suspend fun revoke(current: Credentials) {
        try {
            api.logout(current.refreshToken, current.token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger().log("logout: refresh token not revoked: ${e.message}")
        }
    }

    fun end(expected: Credentials? = null, expectedGeneration: Long? = null): Credentials? = synchronized(lock) {
        if ((expected != null && credentials != expected) ||
            (expectedGeneration != null && generation != expectedGeneration)
        ) return null
        val previous = credentials
        generation++
        refreshLoop?.cancel()
        refreshLoop = null
        if (expected == null) refreshInFlight?.cancel()
        refreshInFlight = null
        credentials = null
        _state.value = AuthState.LOGGED_OUT
        stored("clear") { storage.clear() }
        previous
    }

    suspend fun ensureFreshToken(): TokenStatus = refreshIfNeeded(force = false)

    /** Concurrent callers share one refresh: the refresh token is single-use. */
    suspend fun refresh(): TokenStatus = refreshIfNeeded(force = true)

    private suspend fun refreshIfNeeded(force: Boolean): TokenStatus {
        val attempt = synchronized(lock) {
            val current = credentials ?: return TokenStatus.REJECTED
            refreshInFlight?.takeIf { it.isActive }?.let { return@synchronized it }
            if (!force && secondsUntilExpiry(current.token) > FRESHNESS_MARGIN_S) return TokenStatus.FRESH
            val version = generation
            scope.async { performRefresh(current, version) }.also { refreshInFlight = it }
        }
        return attempt.await()
    }

    private suspend fun performRefresh(current: Credentials, version: Long): TokenStatus {
        val response = try {
            api.refreshSession(current.refreshToken)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failureCount = synchronized(lock) {
                if (generation != version || credentials != current) {
                    return if (credentials == null) TokenStatus.REJECTED else TokenStatus.FRESH
                }
                ++refreshFailures
            }
            if (e !is java.io.IOException && e !is ObscuraError.Http) throw e
            logger().tokenRefreshFailed(failureCount, e.message ?: e.javaClass.simpleName)
            if (e is ObscuraError.Http && e.status in REJECTION_STATUSES) {
                onRejected(current, version)
                return if (credentials == null) TokenStatus.REJECTED else TokenStatus.FRESH
            } else {
                return TokenStatus.UNAVAILABLE
            }
        }
        return synchronized(lock) {
            if (generation != version || credentials != current) {
                return@synchronized if (credentials == null) TokenStatus.REJECTED else TokenStatus.FRESH
            }
            if (Jwt.userId(response.token) != current.userId || Jwt.expiresAt(response.token) == null ||
                (response.deviceId != null && response.deviceId != current.deviceId)
            ) throw ObscuraError.ProtocolViolation("Refresh returned credentials for another session")
            val refreshed = current.copy(token = response.token, refreshToken = response.refreshToken)
            stored("save") { storage.save(refreshed.toMap()) }
            credentials = refreshed
            refreshFailures = 0
            TokenStatus.FRESH
        }
    }

    private fun start(new: Credentials) {
        synchronized(lock) {
            stored("save") { storage.save(new.toMap()) }
            generation++
            refreshInFlight?.cancel()
            refreshInFlight = null
            credentials = new
            refreshFailures = 0
            refreshLoop?.cancel()
            refreshLoop = scope.launch { refreshAheadOfExpiry() }
            _state.value = AuthState.AUTHENTICATED
        }
    }

    private suspend fun refreshAheadOfExpiry() {
        while (true) {
            delay(if (refreshFailures == 0) refreshDelayMs() else retryDelayMs(refreshFailures))
            try {
                if (refresh() == TokenStatus.REJECTED) return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger().log("SESSION automatic refresh stopped: ${e.message}")
                return
            }
        }
    }

    /** 80% of the token's remaining lifetime, at least 5 s. */
    private fun refreshDelayMs(): Long {
        val token = credentials?.token ?: return MIN_REFRESH_DELAY_MS
        return (secondsUntilExpiry(token) * 800).coerceAtLeast(MIN_REFRESH_DELAY_MS)
    }

    private fun secondsUntilExpiry(token: String): Long =
        (Jwt.expiresAt(token) ?: 0L) - System.currentTimeMillis() / 1000

    private fun validateLogin(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) throw ObscuraError.InvalidArgument("Username and password must not be blank")
    }

    private suspend fun requireUnprovisioned() {
        if (devices.getIdentity() != null || signalStore.hasIdentity()) {
            throw ObscuraError.InvalidArgument("Wipe the local device before provisioning another")
        }
    }

    private inline fun <T> stored(operation: String, block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw ObscuraError.StorageFailed("Session storage $operation failed: ${e.message}", e)
    }

    private companion object {
        val REJECTION_STATUSES = setOf(401, 403)
        const val FRESHNESS_MARGIN_S = 60L
        const val MIN_REFRESH_DELAY_MS = 5_000L

        /** 5 s doubling to 5 min. */
        fun retryDelayMs(failures: Int): Long = (5_000L shl (failures - 1).coerceAtMost(6)).coerceAtMost(300_000L)
    }
}
