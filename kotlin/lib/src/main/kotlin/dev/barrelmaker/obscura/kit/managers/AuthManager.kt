package dev.barrelmaker.obscura.kit.managers

import dev.barrelmaker.obscura.kit.AuthState
import dev.barrelmaker.obscura.kit.ObscuraConfig
import dev.barrelmaker.obscura.kit.ObscuraLogger
import dev.barrelmaker.obscura.kit.crypto.toBase64
import dev.barrelmaker.obscura.kit.managers.SignalKeyUtils.toApiJson
import dev.barrelmaker.obscura.kit.network.GatewayConnection
import dev.barrelmaker.obscura.kit.network.HttpException
import dev.barrelmaker.obscura.kit.network.LoginScenario
import dev.barrelmaker.obscura.kit.network.ProvisionDeviceRequest
import dev.barrelmaker.obscura.kit.stores.DeviceIdentityData
import dev.barrelmaker.obscura.kit.stores.OwnDeviceData
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
    private val setDisconnected: () -> Unit,
    private val loggerProvider: () -> ObscuraLogger,
    private val onLogout: suspend () -> Unit,
    private val onWipeDevice: suspend () -> Unit,
    private val onSessionChanged: () -> Unit
) {
    private val session get() = ctx.session
    private val api get() = ctx.api
    private val signalStore get() = ctx.signalStore
    private val messenger get() = ctx.messenger
    private val devices get() = ctx.devices
    private val refreshInProgress = java.util.concurrent.atomic.AtomicReference<Deferred<Boolean>?>(null)
    private var consecutiveRefreshFailures = 0
    private val MAX_REFRESH_FAILURES = 5
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

        messenger.mapDevice(
            requireNotNull(session.deviceId) { "deviceId not set - register failed to provision device" },
            requireNotNull(session.userId) { "userId not set - register failed to resolve user" },
        )

        devices.storeIdentity(DeviceIdentityData(
            deviceId = requireNotNull(session.deviceId) { "deviceId not set - register failed to provision device" },
        ))

        // DeviceAnnounce and link approval require a complete own-device registry.
        devices.addOwnDevice(OwnDeviceData(
            deviceId = requireNotNull(session.deviceId),
            deviceName = config.deviceName,
        ))

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

                messenger.mapDevice(deviceId, session.userId!!)

                setAuthState(AuthState.AUTHENTICATED)
                delay(config.authRateLimitDelayMs)
                return LoginScenario.EXISTING_DEVICE
            } catch (e: HttpException) {
                // The user-scoped login below classifies 401/403.
                when (e.statusCode) {
                    401, 403 -> Unit
                    404 -> return LoginScenario.USER_NOT_FOUND
                    else -> throw e
                }
            }
        }

        try {
            api.loginWithDevice(username, password, null)
        } catch (e: HttpException) {
            return when (e.statusCode) {
                401, 403 -> LoginScenario.INVALID_CREDENTIALS
                404 -> LoginScenario.USER_NOT_FOUND
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

        messenger.mapDevice(
            requireNotNull(session.deviceId) { "deviceId not set - loginAndProvision failed to provision device" },
            requireNotNull(session.userId) { "userId not set - loginAndProvision failed to resolve user" },
        )

        devices.storeIdentity(DeviceIdentityData(
            deviceId = requireNotNull(session.deviceId) { "deviceId not set - loginAndProvision failed to provision device" },
        ))

        // Approval later reconciles the full account list.
        devices.addOwnDevice(OwnDeviceData(
            deviceId = requireNotNull(session.deviceId),
            deviceName = deviceName,
        ))

        // Wait for DEVICE_LINK_APPROVAL only if another device exists to send it.
        val serverDevices = api.listDevices()
        val hasApprover = (0 until serverDevices.length()).any {
            serverDevices.getJSONObject(it).getString("deviceId") != session.deviceId
        }
        setAuthState(if (hasApprover) AuthState.PENDING_APPROVAL else AuthState.AUTHENTICATED)
        delay(config.authRateLimitDelayMs)
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

        if (deviceId != null) {
            messenger.mapDevice(deviceId, userId)
        }

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

    fun startTokenRefresh() {
        tokenRefreshJob?.cancel()
        tokenRefreshJob = scope.launch {
            while (isActive) {
                val delayMs = getTokenRefreshDelay()
                delay(delayMs)
                refreshTokens()
            }
        }
    }

    suspend fun refreshTokens(): Boolean {
        refreshInProgress.get()?.let { return it.await() }

        val deferred = scope.async {
            try {
                val rt = session.refreshToken ?: return@async false
                val result = api.refreshSession(rt)
                api.token = result.token
                session.refreshToken = result.refreshToken
                consecutiveRefreshFailures = 0
                // Persist the rotated (single-use) refresh token — the background
                // refresh loop otherwise leaves a consumed token in storage → 401
                // on next launch. Mirrors iOS refreshTokenNow → onSessionChanged.
                onSessionChanged()
                true
            } catch (e: Exception) {
                consecutiveRefreshFailures++
                loggerProvider().tokenRefreshFailed(consecutiveRefreshFailures, e.message ?: "unknown")
                if (consecutiveRefreshFailures >= MAX_REFRESH_FAILURES) {
                    setDisconnected()
                }
                false
            }
        }

        refreshInProgress.set(deferred)
        try {
            return deferred.await()
        } finally {
            refreshInProgress.set(null)
        }
    }

    suspend fun ensureFreshToken(): Boolean {
        if (!isTokenExpired(60)) return true
        return refreshTokens()
    }

    private fun isTokenExpired(bufferSeconds: Long = 0): Boolean {
        val token = api.token ?: return true
        val payload = api.decodeToken(token) ?: return true
        val exp = payload.optLong("exp", 0)
        if (exp == 0L) return true
        val now = System.currentTimeMillis() / 1000
        return (exp - now) <= bufferSeconds
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
