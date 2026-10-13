package dev.barrelmaker.obscura.kit.managers

import dev.barrelmaker.obscura.kit.AuthState
import dev.barrelmaker.obscura.kit.ObscuraConfig
import dev.barrelmaker.obscura.kit.ObscuraLogger
import dev.barrelmaker.obscura.kit.TokenStatus
import dev.barrelmaker.obscura.kit.crypto.toBase64
import dev.barrelmaker.obscura.kit.managers.SignalKeyUtils.toApiJson
import dev.barrelmaker.obscura.kit.network.GatewayConnection
import dev.barrelmaker.obscura.kit.network.HttpException
import dev.barrelmaker.obscura.kit.network.LoginScenario
import dev.barrelmaker.obscura.kit.network.ProvisionDeviceRequest
import dev.barrelmaker.obscura.kit.stores.DeviceData
import dev.barrelmaker.obscura.kit.stores.DeviceIdentityData
import kotlinx.coroutines.*

/**
 * Handles register, login, loginAndProvision, logout, session restore, and token refresh.
 */
internal class AuthManager(
    private val ctx: ClientContext,
    private val config: ObscuraConfig,
    private val gateway: GatewayConnection,
    private val scope: CoroutineScope,
    private val setAuthState: (AuthState) -> Unit,
    /** Signs out and forgets the stored session; called once the server refuses the refresh token. */
    private val onSessionRejected: suspend () -> Unit,
    private val loggerProvider: () -> ObscuraLogger,
    private val onLogout: suspend () -> Unit,
    private val onWipeDevice: suspend () -> Unit,
    private val onSessionChanged: () -> Unit
) {
    private val session get() = ctx.session
    private val api get() = ctx.api
    private val signalStore get() = ctx.signalStore
    private val devices get() = ctx.devices
    private val refreshInProgress = java.util.concurrent.atomic.AtomicReference<Deferred<TokenStatus>?>(null)
    private var consecutiveRefreshFailures = 0
    var tokenRefreshJob: Job? = null

    suspend fun register(username: String, password: String) {
        session.username = username
        val (identityKeyPair, regId) = signalStore.generateIdentity()
        val signedPreKey = SignalKeyUtils.generateSignedPreKey(signalStore, identityKeyPair, 1)
        val oneTimePreKeys = SignalKeyUtils.generateOneTimePreKeys(signalStore, 1, 100)
        val regResult = api.registerUser(username, password)
        api.token = regResult.token
        val provResult = api.provisionDevice(ProvisionDeviceRequest(
            name = config.deviceName,
            identityKey = identityKeyPair.publicKey.serialize().toBase64(),
            registrationId = regId,
            signedPreKey = signedPreKey.toApiJson(),
            oneTimePreKeys = oneTimePreKeys.toApiJson()
        ))
        val deviceToken = provResult.token
        api.token = deviceToken
        session.refreshToken = provResult.refreshToken
        session.userId = api.getUserId(deviceToken)
        session.deviceId = provResult.deviceId.ifEmpty { null } ?: api.getDeviceId(deviceToken)

        recordProvisionedDevice(config.deviceName)

        setAuthState(AuthState.AUTHENTICATED)
        delay(config.authRateLimitDelayMs)
    }

    /**
     * Only [LoginScenario.EXISTING_DEVICE] changes client state. Every other outcome leaves the
     * client logged out so the app can choose between loginAndProvision(), register() and an error.
     */
    suspend fun login(username: String, password: String): LoginScenario {
        val identity = devices.getIdentity()

        if (identity?.deviceId != null) {
            try {
                val result = api.loginWithDevice(username, password, identity.deviceId)
                val token = result.token
                // The server answers an unknown deviceId with a user-scoped token rather than an error.
                val deviceId = result.deviceId ?: api.getDeviceId(token) ?: return LoginScenario.DEVICE_MISMATCH
                api.token = token
                session.refreshToken = result.refreshToken
                session.userId = api.getUserId(token)
                session.deviceId = deviceId
                session.username = username

                setAuthState(AuthState.AUTHENTICATED)
                delay(config.authRateLimitDelayMs)
                return LoginScenario.EXISTING_DEVICE
            } catch (e: HttpException) {
                // The user-scoped login below classifies 401/403.
                if (e.statusCode != 401 && e.statusCode != 403) throw e
            }
        }

        try {
            api.loginWithDevice(username, password, null)
        } catch (e: HttpException) {
            return when (e.statusCode) {
                401, 403 -> LoginScenario.INVALID_CREDENTIALS
                else -> throw e
            }
        }
        return if (identity?.deviceId != null) LoginScenario.DEVICE_MISMATCH else LoginScenario.NEW_DEVICE
    }

    suspend fun loginAndProvision(username: String, password: String, deviceName: String = "Device 2") {
        session.username = username

        val loginResult = api.loginWithDevice(username, password, null)
        api.token = loginResult.token
        session.userId = api.getUserId(loginResult.token)

        val (identityKeyPair, regId) = signalStore.generateIdentity()

        val signedPreKey = SignalKeyUtils.generateSignedPreKey(signalStore, identityKeyPair, 1)
        val oneTimePreKeys = SignalKeyUtils.generateOneTimePreKeys(signalStore, 1, 100)

        val provResult = api.provisionDevice(ProvisionDeviceRequest(
            name = deviceName,
            identityKey = identityKeyPair.publicKey.serialize().toBase64(),
            registrationId = regId,
            signedPreKey = signedPreKey.toApiJson(),
            oneTimePreKeys = oneTimePreKeys.toApiJson()
        ))

        val deviceToken = provResult.token
        api.token = deviceToken
        session.refreshToken = provResult.refreshToken
        session.deviceId = provResult.deviceId.ifEmpty { null } ?: api.getDeviceId(deviceToken)

        // Approval later reconciles the full account list.
        recordProvisionedDevice(deviceName)

        // Wait for DEVICE_LINK_APPROVAL only if another device exists to send it.
        val serverDevices = api.listDevices()
        val hasApprover = (0 until serverDevices.length()).any {
            serverDevices.getJSONObject(it).getString("deviceId") != session.deviceId
        }
        setAuthState(if (hasApprover) AuthState.PENDING_APPROVAL else AuthState.AUTHENTICATED)
        delay(config.authRateLimitDelayMs)
    }

    /** Store this device as the identity `login` checks, and list it as one of the user's devices. */
    private suspend fun recordProvisionedDevice(deviceName: String) {
        val deviceId = requireNotNull(session.deviceId) { "deviceId not set - device was not provisioned" }
        val userId = requireNotNull(session.userId) { "userId not set - user was not resolved" }
        devices.storeIdentity(DeviceIdentityData(deviceId))
        devices.add(DeviceData(userId, deviceId, deviceName))
    }

    fun restoreSession(
        token: String,
        refreshToken: String?,
        userId: String,
        deviceId: String?,
        username: String?,
    ) {
        api.token = token
        session.refreshToken = refreshToken
        session.userId = userId
        session.deviceId = deviceId
        session.username = username

        setAuthState(AuthState.AUTHENTICATED)
    }

    fun hasSession(): Boolean = api.token != null && session.userId != null

    suspend fun logout() {
        tokenRefreshJob?.cancel()
        onLogout()
        api.token = null
        session.userId = null
        session.deviceId = null
        session.username = null
        session.refreshToken = null
        setAuthState(AuthState.LOGGED_OUT)
    }

    suspend fun wipeDevice() {
        tokenRefreshJob?.cancel()
        onWipeDevice()
        api.token = null
        session.userId = null
        session.deviceId = null
        session.username = null
        session.refreshToken = null
        setAuthState(AuthState.LOGGED_OUT)
    }

    /**
     * Refresh ahead of expiry for as long as the session lasts. A failed refresh is retried with
     * backoff; only a rejection ends the loop, because only a rejection ends the session.
     */
    fun startTokenRefresh() {
        tokenRefreshJob?.cancel()
        tokenRefreshJob = scope.launch {
            while (isActive) {
                delay(if (consecutiveRefreshFailures == 0) getTokenRefreshDelay() else retryDelayMs(consecutiveRefreshFailures))
                if (refreshTokens() == TokenStatus.REJECTED) break
            }
        }
    }

    /**
     * Exchange the refresh token for a new pair. A 401/403 (or no refresh token at all) means the
     * session is over: sign out. Anything else (offline, timeout, 5xx) keeps the session.
     */
    suspend fun refreshTokens(): TokenStatus {
        refreshInProgress.get()?.let { return it.await() }

        val deferred = scope.async {
            val rt = session.refreshToken ?: return@async rejectSession()
            try {
                val result = api.refreshSession(rt)
                api.token = result.token
                session.refreshToken = result.refreshToken
                consecutiveRefreshFailures = 0
                // Persist the rotated (single-use) refresh token — the background
                // refresh loop otherwise leaves a consumed token in storage → 401
                // on next launch. Mirrors iOS refreshTokenNow → onSessionChanged.
                onSessionChanged()
                TokenStatus.FRESH
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                consecutiveRefreshFailures++
                loggerProvider().tokenRefreshFailed(consecutiveRefreshFailures, e.message ?: "unknown")
                if (e is HttpException && e.statusCode in REJECTION_STATUSES) rejectSession() else TokenStatus.UNAVAILABLE
            }
        }

        refreshInProgress.set(deferred)
        try {
            return deferred.await()
        } finally {
            refreshInProgress.set(null)
        }
    }

    suspend fun ensureFreshToken(): TokenStatus {
        if (api.token == null) return TokenStatus.REJECTED
        if (!isTokenExpired(60)) return TokenStatus.FRESH
        return refreshTokens()
    }

    private suspend fun rejectSession(): TokenStatus {
        onSessionRejected()
        return TokenStatus.REJECTED
    }

    /** 5 s doubling to a 5 min cap. */
    private fun retryDelayMs(failures: Int): Long = (5_000L shl (failures - 1).coerceAtMost(6)).coerceAtMost(300_000L)

    private fun isTokenExpired(bufferSeconds: Long = 0): Boolean {
        val token = api.token ?: return true
        val payload = api.decodeToken(token) ?: return true
        val exp = payload.optLong("exp", 0)
        if (exp == 0L) return true
        val now = System.currentTimeMillis() / 1000
        return (exp - now) <= bufferSeconds
    }

    private companion object {
        val REJECTION_STATUSES = setOf(401, 403)
    }

    private fun getTokenRefreshDelay(): Long {
        val token = api.token ?: return 30_000
        val payload = api.decodeToken(token) ?: return 30_000
        val exp = payload.optLong("exp", 0)
        if (exp == 0L) return 30_000
        val now = System.currentTimeMillis() / 1000
        val ttl = exp - now
        if (ttl <= 0) return 5_000
        return (ttl * 800).coerceAtLeast(5_000)
    }
}
