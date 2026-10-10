package dev.barrelmaker.obscura.kit

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.barrelmaker.obscura.kit.crypto.LinkCode
import dev.barrelmaker.obscura.kit.crypto.SignalStore
import dev.barrelmaker.obscura.kit.crypto.UuidCodec
import dev.barrelmaker.obscura.kit.crypto.toBase64
import dev.barrelmaker.obscura.kit.db.ObscuraDatabase
import dev.barrelmaker.obscura.kit.managers.*
import dev.barrelmaker.obscura.kit.managers.SignalKeyUtils.toApiJson
import dev.barrelmaker.obscura.kit.messaging.Messenger
import dev.barrelmaker.obscura.kit.network.APIClient
import dev.barrelmaker.obscura.kit.network.GatewayConnection
import dev.barrelmaker.obscura.kit.network.GatewayState
import dev.barrelmaker.obscura.kit.network.LoginScenario
import dev.barrelmaker.obscura.kit.network.UploadDeviceKeysRequest
import dev.barrelmaker.obscura.kit.wire.TypingTracker
import dev.barrelmaker.obscura.kit.wire.WireCodec
import dev.barrelmaker.obscura.kit.wire.PayloadDisposition
import dev.barrelmaker.obscura.kit.wire.payloadDisposition
import dev.barrelmaker.obscura.kit.stores.*
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dev.barrelmaker.obscura.kit.persistence.NoOpSessionStorage
import dev.barrelmaker.obscura.kit.persistence.SessionStorage
import obscura.client.v1.Client.ClientMessage
import obscura.client.v1.typingSignal
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class MessageWakeEvent internal constructor(
    val type: String,
    internal val username: String = "",
    internal val sourceUserId: String = "",
    internal val senderDeviceId: String? = null,
    /** For APP_ENTRY messages: the opaque model key; null for non-sync types. */
    val model: String? = null,
    /** Test-only access to the decoded wire message. Applications drain the durable inbox. */
    internal val raw: ClientMessage? = null,
)

/**
 * Public connection state. 1:1 with the network layer's [GatewayState] and with
 * the Swift kit's ConnectionState (disconnected/connecting/reconnecting/connected):
 *   CONNECTING   — first connection attempt in progress
 *   RECONNECTING — a dropped connection is being retried (backoff)
 *   CONNECTED    — websocket open
 */
enum class ConnectionState { DISCONNECTED, CONNECTING, RECONNECTING, CONNECTED }

enum class AuthState { LOGGED_OUT, PENDING_APPROVAL, AUTHENTICATED }

/**
 * Create with default JVM in-memory driver (for tests).
 * For Android production, pass an encrypted AndroidSqliteDriver:
 *
 *   val driver = AndroidSqliteDriver(
 *       ObscuraDatabase.Schema,
 *       context,
 *       "obscura.db",
 *       factory = SupportSQLiteOpenHelper.Factory(SQLCipherOpenHelperFactory(passphrase))
 *   )
 *   val client = ObscuraClient(config, driver)
 */
class ObscuraClient(
    val config: ObscuraConfig,
    externalDriver: SqlDriver? = null,
    val sessionStorage: SessionStorage = NoOpSessionStorage
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _authState = MutableStateFlow(AuthState.LOGGED_OUT)
    val authState: StateFlow<AuthState> = _authState

    private val _friendList = MutableStateFlow<List<FriendData>>(emptyList())
    internal val friendList: StateFlow<List<FriendData>> = _friendList

    private val _friendsChanged = MutableSharedFlow<Unit>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Payload-free wake event. Call [getFriends] for the canonical current rows. */
    val friendsChanged: SharedFlow<Unit> = _friendsChanged.asSharedFlow()

    private val driver = externalDriver ?: if (config.databasePath != null) {
        JdbcSqliteDriver("jdbc:sqlite:${config.databasePath}")
    } else {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    }
    internal val db: ObscuraDatabase

    internal val signalStore: SignalStore
    internal val api = APIClient(config.apiUrl)
    internal val gateway: GatewayConnection

    private val friends: FriendStore
    internal val devices: DeviceStore
    internal val messenger: Messenger

    /**
     * The durable inbox (`KIT_API.md` §3) — the thin kit's receive API.
     *
     * Four methods: peek / consume / discard / depth. The kit writes rows before it acks; the app
     * drains them. There is no insert, because the kit is the only writer.
     */
    val inbox: InboxStore

    /**
     * Raw storage for application entries (`KIT_API.md` §8.1) — the other half of the
     * thin kit's app-facing surface. `inbox` is how messages arrive; this is where the app keeps
     * what it made of them.
     */
    val entries: EntryStore

    private val typingTracker: TypingTracker

    // Session — shared mutable state
    private val session = ClientSession()

    // Managers
    private val authManager: AuthManager
    private val messageSender: MessageSender
    private val friendshipManager: FriendshipManager
    private val contentService: ContentService
    private val deviceManager: DeviceManager

    // Identity — delegate to session
    var userId: String?
        get() = session.userId
        private set(value) { session.userId = value }
    var deviceId: String?
        get() = session.deviceId
        private set(value) { session.deviceId = value }
    var username: String?
        get() = session.username
        private set(value) { session.username = value }
    var refreshToken: String?
        get() = session.refreshToken
        private set(value) { session.refreshToken = value }
    val registrationId: Int get() = signalStore.getLocalRegistrationId()
    val token: String? get() = api.token

    /** Structured logger for security events. Set to a custom implementation for production. */
    var logger: ObscuraLogger = NoOpLogger

    /**
     * The kit's inbound-message stream and the intended public consumer API:
     * exactly one consumer should drain this channel (e.g. the app's
     * process-scoped session), classify each [MessageWakeEvent], and fan out to
     * UI/notifications. Buffered so messages that arrive before a consumer
     * attaches (e.g. an FCM cold-start) are not dropped.
     */
    val incomingMessages = Channel<MessageWakeEvent>(capacity = 1000)

    /** Debug log — ring buffer of last 200 events. Thread-safe. */
    internal val debugLog = ConcurrentLinkedDeque<String>()
    private fun log(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        debugLog.addFirst("[$ts] $msg")
        while (debugLog.size > 200) debugLog.removeLast()
    }

    private var envelopeJob: Job? = null

    // M13: Decrypt rate limiting per sender
    private val decryptFailures = mutableMapOf<String, Pair<Int, Long>>() // senderId -> (count, windowStart)

    private val pushDrainMutex = Mutex()
    private val processedEnvelopeCount = AtomicLong()
    private val lastProcessedEnvelopeAtMs = AtomicLong()

    init {
        if (externalDriver == null) {
            ObscuraDatabase.Schema.create(driver)
        }
        // Every driver, including an app-supplied one: `EntryStore.erase` relies on freed pages
        // being zeroed.
        try { driver.pragma("PRAGMA secure_delete = ON") } catch (e: Exception) { log("PRAGMA secure_delete failed: ${e.message}") }
        db = ObscuraDatabase(driver)

        signalStore = SignalStore(db)
        signalStore.onIdentityChanged = { address, _, _ ->
            logger.identityChanged(address)
        }
        friends = FriendStore(db)
        devices = DeviceStore(db)
        messenger = Messenger(signalStore, api)
        inbox = InboxStore(db)
        entries = EntryStore(db, driver)
        // A discard is data loss the app chose deliberately, and §3.3 rule 5 requires it be logged
        // as a security-relevant event rather than being the quiet path.
        inbox.onDiscard = { ids, reason ->
            logger.log("INBOX DISCARD ${ids.size} row(s) reason=\"$reason\" ids=$ids")
            log("INBOX DISCARD ${ids.size} row(s): $reason")
        }

        typingTracker = TypingTracker()
        gateway = GatewayConnection(api, scope)


        // Create ClientContext — shared dependencies for all managers
        val ctx = ClientContext(
            session = session,
            api = api,
            signalStore = signalStore,
            messenger = messenger,
            friends = friends,
            devices = devices,
            db = db
        )

        // Create managers — order matters: AuthManager before MessageSender
        authManager = AuthManager(
            ctx = ctx,
            config = config,
            gateway = gateway,
            scope = scope,
            setAuthState = { _authState.value = it },
            setDisconnected = { disconnect() },
            loggerProvider = { logger },
            onLogout = {
                envelopeJob?.cancel()
                gateway.disconnect() // fires onStateChanged → _connectionState = DISCONNECTED
                // Data stays — logout is not a wipe. Login again restores full state.
            },
            onWipeDevice = {
                envelopeJob?.cancel()
                gateway.disconnect() // fires onStateChanged → _connectionState = DISCONNECTED
                db.friendQueries.deleteAll()
                db.deviceQueries.deleteAllDevices()
                db.deviceQueries.deleteIdentity()
                db.signalKeyQueries.deleteLocalIdentity()
                db.signalKeyQueries.deleteAllSignalData()
                db.signalKeyQueries.deleteAllPreKeys()
                db.signalKeyQueries.deleteAllSignedPreKeys()
                db.signalKeyQueries.deleteAllSessions()
                db.signalKeyQueries.deleteAllSenderKeys()
                db.modelEntryQueries.deleteAllEntries()
                // The §3.3 rule 2 carve-out: a device wipe must also destroy the inbox's decrypted
                // plaintext. The whole table is cleared so this remains a security operation, not
                // the eviction policy §3.4 refuses to add.
                // Keep the security carve-out behind InboxStore rather than exposing its query.
                inbox.wipe()
            },
            onSessionChanged = { persistSession() }
        )

        messageSender = MessageSender(messenger, authManager)
        ctx.messageSender = messageSender

        // Wire gateway reconnect token refresh
        gateway.ensureFreshToken = { authManager.ensureFreshToken() }

        // connectionState is a pure projection of the real socket state. The
        // gateway owns the socket lifecycle (connect, background drop, auto-
        // reconnect); this callback is the SINGLE writer of _connectionState, so
        // there's exactly one source of truth. It fires synchronously from the
        // gateway (see GatewayConnection.setState), which is why connect() — after
        // awaiting the open signal — observes CONNECTED without a race.
        gateway.onStateChanged = { gs ->
            _connectionState.value = when (gs) {
                GatewayState.DISCONNECTED -> ConnectionState.DISCONNECTED
                GatewayState.CONNECTING   -> ConnectionState.CONNECTING
                GatewayState.RECONNECTING -> ConnectionState.RECONNECTING
                GatewayState.CONNECTED    -> ConnectionState.CONNECTED
            }
        }

        friendshipManager = FriendshipManager(ctx = ctx)

        contentService = ContentService(ctx = ctx)

        deviceManager = DeviceManager(
            ctx = ctx,
            announceDevicesCallback = { announceDevices() }
        )

        // Reactive observation — auto-updates StateFlows when DB changes
        startDatabaseObservation()
    }

    private fun startDatabaseObservation() {
        scope.launch {
            db.friendQueries.selectAll()
                .asFlow()
                .mapToList(Dispatchers.IO)
                .map { rows -> rows.map { it.toObservedFriendData() } }
                .collect {
                    _friendList.value = it
                    _friendsChanged.tryEmit(Unit)
                }
        }

    }

    /** Current friend rows. Aggregate wake events intentionally carry no copies of this payload. */
    fun getFriends(): List<FriendData> = _friendList.value

    /** Snapshot of the bounded debug ring, newest first. Debug lines are never live events. */
    fun getDebugLog(): List<String> = debugLog.toList()

    suspend fun register(username: String, password: String) {
        authManager.register(username, password)
    }
    suspend fun login(username: String, password: String): LoginScenario =
        authManager.login(username, password)
    suspend fun loginAndProvision(username: String, password: String, deviceName: String = "Device 2") =
        authManager.loginAndProvision(username, password, deviceName)

    fun restoreSession(
        token: String,
        refreshToken: String?,
        userId: String,
        deviceId: String?,
        username: String?,
    ) {
        authManager.restoreSession(token, refreshToken, userId, deviceId, username)
    }

    fun hasSession(): Boolean = authManager.hasSession()

    /**
     * Log out: tears down the connection and forgets the session, INCLUDING the
     * persisted [sessionStorage] blob, so the app won't try to restore it next
     * launch. Local data (friends, messages, entries, inbox) is kept — see [wipeDevice] /
     * [fullLogout] to also erase that. Symmetric with [persistSession].
     */
    suspend fun logout() {
        authManager.logout()
        sessionStorage.clear()
    }
    suspend fun wipeDevice() = authManager.wipeDevice()
    suspend fun ensureFreshToken(): Boolean = authManager.ensureFreshToken()

    // ─── Session Persistence (kit-owned) ──────────────────

    /** Persist current session to storage. Auto-called on auth/connect. */
    fun persistSession() {
        // Merge onto existing storage rather than replacing it, so any non-session key the host app
        // keeps in the same blob survives a session-only save regardless of whether the
        // SessionStorage impl patches keys or overwrites wholesale.
        val data = (sessionStorage.load()?.toMutableMap() ?: mutableMapOf()).apply {
            put("token", token)
            put("refreshToken", refreshToken)
            put("userId", userId)
            put("deviceId", deviceId)
            put("username", username)
        }
        sessionStorage.save(data)
        log("SESSION persisted user=$username")
    }

    /**
     * Restore session from storage and connect.
     * Returns true if session was restored and connected.
     */
    suspend fun restorePersistedSession(): Boolean {
        val saved = sessionStorage.load() ?: return false
        val savedToken = saved["token"] as? String ?: return false
        val savedUserId = saved["userId"] as? String ?: return false
        if (savedToken.isBlank() || savedUserId.isBlank()) return false

        log("SESSION restoring user=${saved["username"]}")
        restoreSession(
            token = savedToken,
            refreshToken = saved["refreshToken"] as? String,
            userId = savedUserId,
            deviceId = saved["deviceId"] as? String,
            username = saved["username"] as? String,
        )


        // Refresh token + connect
        try {
            val fresh = ensureFreshToken()
            if (!fresh) {
                log("SESSION token refresh failed — clearing")
                sessionStorage.clear()
                return false
            }
            connect()
            persistSession() // save refreshed tokens
            log("SESSION restored and connected")
            return true
        } catch (e: Exception) {
            log("SESSION restore connect failed: ${e.message}")
            return false
        }
    }

    // ─── Facade Methods (bridges call these 1:1) ────────────

    /**
     * Decode a friend code and befriend the user. See [FriendCode] for the format.
     *
     * Delegate to [FriendCode] so URL-safe base64 and required-field validation have one
     * implementation.
     *
     * The soft-hyphen and whitespace strip is kept here — it is about text that survived a copy out
     * of an iOS share sheet, not about the encoding.
     */
    suspend fun addFriendByCode(code: String) {
        val cleaned = code
            .replace("\u00AD", "") // strip soft hyphens from iOS copy
            .replace("\\s".toRegex(), "")
        val decoded = FriendCode.decode(cleaned)
        log("ADD_FRIEND_BY_CODE ${decoded.username} (${decoded.userId})")
        befriend(decoded.userId, decoded.username)
    }

    /** Generate a friend code for sharing. See [FriendCode]. */
    fun friendCode(): String {
        val uid = userId ?: throw ObscuraError.NotAuthenticated()
        val uname = username ?: throw ObscuraError.NotAuthenticated()
        return FriendCode.encode(uid, uname)
    }

    /**
     * Full logout — handles ALL teardown in correct order.
     * Bridges call this single method instead of orchestrating cleanup.
     */
    suspend fun fullLogout() {
        log("FULL_LOGOUT start")
        envelopeJob?.cancel()
        authManager.tokenRefreshJob?.cancel()
        preKeyStatusJob?.cancel()
        // SignalManager's own scope outlives this object otherwise: every `receive` launches a
        // 3.1s expiry coroutine, and after a logout those keep running (and keep mutating typing
        // state) for a user who is gone.
        typingTracker.shutdown()
        gateway.disconnect() // fires onStateChanged → _connectionState = DISCONNECTED
        try { authManager.logout() } catch (e: Exception) { log("logout during fullLogout failed: ${e.message}") }
        _authState.value = AuthState.LOGGED_OUT
        _friendList.value = emptyList()
        sessionStorage.clear()
        log("FULL_LOGOUT complete")
    }

    // ─── Connect / Disconnect ───────────────────────────────

    // Serializes connect() so a foreground ensureConnected() and a bridge connect()
    // (or overlapping lifecycle events) can't run the connect body concurrently.
    private val connectMutex = Mutex()

    suspend fun connect() = connectMutex.withLock {
        if (_connectionState.value == ConnectionState.CONNECTED) return@withLock
        log("CONNECT start")
        try {
            ensureFreshToken()
            messenger.rebuildDeviceMap(friends.getAccepted())
            // gateway.connect() drives _connectionState via onStateChanged (the sole
            // writer): CONNECTING now, then CONNECTED on open — set synchronously
            // before this suspends-return — or DISCONNECTED if the open fails (throws).
            gateway.connect()
        } catch (e: Exception) {
            log("CONNECT failed — ${e.message}")
            throw e
        }
        log("CONNECT ok — websocket open")
        startEnvelopeLoop()
        authManager.startTokenRefresh()
        startPreKeyStatusListener()
        persistSession() // auto-save refreshed tokens
    }

    /**
     * Idempotent reconnect entrypoint for app lifecycle events (e.g. foreground
     * resume). Reconnects only when authenticated and fully disconnected, so the
     * app can call it unconditionally on resume: it no-ops while a connect or the
     * gateway's own auto-reconnect (CONNECTING) is already in flight, and only
     * kicks off a fresh connect when the socket is genuinely down.
     */
    suspend fun ensureConnected() {
        if (authState.value != AuthState.AUTHENTICATED) return
        if (connectionState.value != ConnectionState.DISCONNECTED) return
        connect()
    }

    fun disconnect() {
        log("DISCONNECT")
        authManager.tokenRefreshJob?.cancel()
        envelopeJob?.cancel()
        // Without this it keeps consuming gateway.preKeyStatus and calling replenishPreKeys() —
        // which POSTs /v1/devices/keys with whatever token is left, i.e. a null one after a logout.
        preKeyStatusJob?.cancel()
        gateway.disconnect() // fires onStateChanged → _connectionState = DISCONNECTED
    }

    // ─── Push Notifications ─────────────────────────────────

    /**
     * Register FCM/APNS push token with server. Requires device-scoped JWT.
     * Safe to call multiple times — server upserts by deviceId.
     */
    suspend fun registerPushToken(token: String) {
        api.registerPushToken(token)
    }

    /**
     * Drain queued envelopes after a silent push wake. Connects if needed, waits up to
     * [timeoutMs] ms (returning early when the receive path stays idle for 500ms), and returns
     * the number of successfully processed envelopes. Does NOT disconnect afterwards — the OS will
     * freeze the app when done.
     *
     * This observes successful receive-path persistence without consuming [incomingMessages].
     * The app remains that channel's single consumer and owns all notification classification.
     *
     * If the socket already looked CONNECTED and the drain still yields nothing, it is torn down and
     * re-established once before giving up — see [shouldForceReconnectAfterPush].
     *
     * Returns zero after both connection attempts fail, which is indistinguishable from a
     * successful drain that processed no envelopes. Connection failure is logged.
     */
    suspend fun processPendingMessages(timeoutMs: Long): Int =
        pushDrainMutex.withLock { performPendingMessageDrain(timeoutMs) }

    private suspend fun performPendingMessageDrain(timeoutMs: Long): Int {
        val processedAtStart = processedEnvelopeCount.get()
        val startedConnected = _connectionState.value == ConnectionState.CONNECTED

        if (!startedConnected && !connectWithOneRetry()) return 0

        val budget = System.currentTimeMillis() + timeoutMs
        awaitEnvelopes(budget)

        var processed = processedEnvelopeCount.get() - processedAtStart

        // An empty drain on a socket that claimed CONNECTED means the push contradicted the
        // connection state; [shouldForceReconnectAfterPush] explains why that is the reading.
        if (shouldForceReconnectAfterPush(
                processed = processed,
                startedConnected = startedConnected,
                lastProcessedAtMs = lastProcessedEnvelopeAtMs.get(),
                nowMs = System.currentTimeMillis(),
                recentActivityWindowMs = PUSH_DRAIN_RECENT_ACTIVITY_MS,
            )
        ) {
            log("PUSH DRAIN contradiction — push arrived but nothing drained on a live socket; reconnecting")
            logger.log("push drain: no envelopes on a connected socket; forcing reconnect")

            // Same budget, not a fresh one: [timeoutMs] is the caller's hard cap, and the first
            // wait returns after ~500ms of idle, so there is normally plenty of it left.
            if (forceReconnect()) {
                awaitEnvelopes(budget)
                processed = processedEnvelopeCount.get() - processedAtStart
            }
        }

        return processed.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * Tear down a socket we no longer believe in and establish a fresh one.
     *
     * [connect] alone cannot do this: it early-returns while the state says CONNECTED, which is
     * exactly the state we are disputing.
     */
    private suspend fun forceReconnect(): Boolean {
        disconnect()
        return connectWithOneRetry()
    }

    /**
     * Connect, retrying one transient failure. Returns false when both attempts fail.
     *
     * The Int return of a drain cannot distinguish "no envelopes" from "could not connect", so
     * failure has to stay observable through the logger.
     */
    private suspend fun connectWithOneRetry(): Boolean {
        try {
            connect()
        } catch (e: Exception) {
            log("PUSH DRAIN connect attempt 1 failed: ${e.message}")
            logger.log("push drain: connect failed (attempt 1/2): ${e.message}")
            delay(PUSH_DRAIN_RECONNECT_RETRY_MS)
            try {
                connect()
            } catch (e2: Exception) {
                log("PUSH DRAIN connect attempt 2 failed — returning zero: ${e2.message}")
                logger.log("push drain ABORTED: could not connect after 2 attempts: ${e2.message}")
                return false
            }
        }
        return true
    }

    /** Wait for the receive path to go quiet for [PUSH_DRAIN_IDLE_THRESHOLD_MS], or until [deadline]. */
    private suspend fun awaitEnvelopes(deadline: Long) {
        var lastActivityAt = System.currentTimeMillis()

        while (System.currentTimeMillis() < deadline) {
            val observedAt = lastProcessedEnvelopeAtMs.get()
            if (observedAt > lastActivityAt) {
                lastActivityAt = observedAt
            }
            if (System.currentTimeMillis() - lastActivityAt > PUSH_DRAIN_IDLE_THRESHOLD_MS) {
                break
            } else {
                delay(50)
            }
        }
    }

    private var preKeyStatusJob: Job? = null
    private fun startPreKeyStatusListener() {
        preKeyStatusJob?.cancel()
        preKeyStatusJob = scope.launch {
            for (status in gateway.preKeyStatus) {
                if (status.oneTimePreKeyCount < status.minThreshold) {
                    replenishPreKeys()
                }
            }
        }
    }

    /**
     * At most one replenishment in flight at a time.
     *
     * [checkAndReplenishPreKeys] fires once per received envelope, so draining a backlog of N
     * messages launched N coroutines that all observed a count below the threshold, all computed
     * the same `highestId + 1` range, and all POSTed 50 keys — N uploads of the same key ids.
     */
    private val replenishInFlight = AtomicBoolean(false)

    private fun checkAndReplenishPreKeys() {
        if (!replenishInFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                if (signalStore.getPreKeyCount() < PREKEY_MIN_COUNT) {
                    replenishPreKeys()
                }
            } catch (e: Exception) { /* non-fatal */ }
            finally { replenishInFlight.set(false) }
        }
    }

    private suspend fun replenishPreKeys() {
        try {
            val highestId = signalStore.getHighestPreKeyId().toInt()
            val newPreKeys = SignalKeyUtils.generateOneTimePreKeys(signalStore, highestId + 1, PREKEY_REPLENISH_COUNT)

            val spkRecord = signalStore.loadSignedPreKey(1)

            api.uploadDeviceKeys(UploadDeviceKeysRequest(
                identityKey = signalStore.getIdentityKeyPair().publicKey.serialize().toBase64(),
                registrationId = signalStore.getLocalRegistrationId(),
                signedPreKey = spkRecord.toApiJson(),
                oneTimePreKeys = newPreKeys.toApiJson()
            ))
        } catch (e: Exception) {
            logger.preKeyReplenishFailed(e.message ?: "unknown")
        }
    }

    private fun startEnvelopeLoop() {
        envelopeJob?.cancel()
        envelopeJob = scope.launch {
            for (envelope in gateway.envelopes) {
                // The envelope carries a sender user hint and the device UUID that selects the
                // inbound Signal session. The friend graph supplies only the display name.
                val senderId = try {
                    val bytes = envelope.senderId.toByteArray()
                    if (bytes.size != 16) null
                    else UuidCodec.bytesToUuid(bytes).toString()
                } catch (_: Exception) { null }

                if (senderId == null) {
                    // No sending user on the wire — a malformed/unroutable envelope. We do NOT guess.
                    // Leave it on the server (persist-then-ack); do not ack.
                    log("RECV FAIL envelope carries no sender_id (left on server, not acked)")
                    continue
                }

                // `Envelope.id` is now the inbox's DEDUPE KEY, so it gets the same length check
                // `sender_id` above and `sender_device_id` in Messenger already get — and for
                // the same reason: SPEC §0.10 treats everything the relay stamps as untrusted.
                //
                // Without this, `UuidCodec.bytesToUuid` returns the NIL UUID for anything shorter
                // than 16 bytes (proto3's default for an unset `bytes` field is empty). Every such
                // envelope would then hash to one key: the first inserts and is acked, and every
                // one after it is suppressed by INSERT OR IGNORE, reported as a duplicate, and
                // ACKED — the server deletes messages that were never stored. Silent, permanent,
                // and remotely triggerable by anything upstream that emits a short id.
                val envelopeIdBytes = envelope.id.toByteArray()
                if (envelopeIdBytes.size != 16) {
                    log("RECV FAIL envelope id is ${envelopeIdBytes.size} bytes, expected 16 " +
                        "(left on server, not acked)")
                    continue
                }
                val envelopeId = UuidCodec.bytesToUuid(envelopeIdBytes).toString()

                // PERSIST-THEN-ACK. An ACK is a DELETE on the server (gateway AckBatcher ->
                // message_service.delete_batch -> DELETE FROM messages). So we ACK ONLY WHAT WE
                // HAVE DURABLY PERSISTED. Every path below that has not persisted the message must
                // skip the ack and leave it on the server, where a fresh MessagePump redelivers it
                // on the next reconnect. There is exactly one ack in this loop, and it is the last
                // thing that happens after a successful decrypt + persist.

                // A rate-limited sender is deferred, not processed or acknowledged.
                if (isDecryptRateLimited(senderId)) {
                    log("RECV BLOCKED rate-limited sender=$senderId (left on server, not acked)")
                    continue
                }

                try {
                    // 1. DECRYPT. Throws on a bad MAC / missing session -> falls to catch -> no ack.
                    // The session is selected by the sending DEVICE (sender_device_id); a valid MAC
                    // then authenticates the sender. decrypted.sourceUserId == envelope.sender_id.
                    val decrypted = messenger.decrypt(envelope)
                    val msg = decrypted.clientMessage
                    val sourceUserId = decrypted.sourceUserId

                    log("RECV ${msg.payloadCase.name} from=${sourceUserId.take(8)} device=${decrypted.senderDeviceId.take(8)}")

                    // 2. PERSIST (durable). routeMessage's handlers write to the SQLDelight store
                    // (e.g. inbox.put; friends.add). This is the source of truth. If it throws, we
                    // fall to catch and do NOT ack, so the message survives on the server.
                    // `envelopeId` is the canonical text form of the 16 validated bytes above —
                    // one encoding, so two spellings of one envelope can never produce two rows.
                    val isNew = routeMessage(msg, sourceUserId, decrypted.senderDeviceId, envelopeId)

                    decryptFailures.remove(senderId)

                    // DISPLAY NAME (SPEC §0.5): for a friend REQUEST/RESPONSE — first contact, sender
                    // not yet a friend — the display username is the legitimate payload bootstrap.
                    // For every other payload the display name is NOT read here; it comes from the
                    // friend graph keyed on sourceUserId when the app renders the conversation.
                    val username = when (msg.payloadCase) {
                        ClientMessage.PayloadCase.FRIEND_REQUEST -> msg.friendRequest.username
                        ClientMessage.PayloadCase.FRIEND_ACCEPT -> friends.get(sourceUserId)?.username ?: ""
                        else -> ""
                    }
                    val received = MessageWakeEvent(
                        type = WireCodec.decodeType(msg.payloadCase),
                        username = username,
                        sourceUserId = sourceUserId,
                        senderDeviceId = decrypted.senderDeviceId,
                        model = msg.takeIf {
                            it.payloadCase == ClientMessage.PayloadCase.APP_ENTRY
                        }?.appEntry?.model?.takeIf(String::isNotBlank),
                        raw = msg
                    )

                    // 3. NOTIFY (best-effort). These emits are wake-up notifications over data that
                    // step 2 has ALREADY durably persisted -- the app model is "event -> refetch
                    // everything from the store", and the store, not this channel/flow, is the
                    // durable delivery path. So a dropped emit loses a NOTIFICATION, never a
                    // message, precisely because persistence happened-before the ack below. We keep
                    // them droppable rather than a suspending send() on purpose: incomingMessages is
                    // a 1000-capacity channel the app does not always drain, and a blocking send
                    // would stall the whole receive loop (and thus all acking) behind a full buffer.
                    // We log a drop so it is observable and never silent.
                    //
                    // Skipped entirely for a redelivery (`isNew == false`). The app already has that
                    // message; announcing it again can make the app post a duplicate notification.
                    lastProcessedEnvelopeAtMs.set(System.currentTimeMillis())
                    processedEnvelopeCount.incrementAndGet()
                    if (isNew && !incomingMessages.trySend(received).isSuccess) {
                        log("RECV NOTE incomingMessages full; dropped a wake-up (data already persisted)")
                    }

                    checkAndReplenishPreKeys()

                    // 4. ACK. Reached only when decrypt AND persist both succeeded — including the
                    // redelivery case, where "persisted" means the row was already there. This is
                    // the sole ack in the loop; the rate-limit early-return and the catch below both
                    // skip it.
                    //
                    // An ack failure is the event the whole persist-then-ack design pivots on: the
                    // server keeps its copy and redelivers, which is safe but is also the first
                    // symptom of a wedged queue. It goes to the structured logger, not only to the
                    // 200-entry in-memory ring buffer where nobody will ever see it.
                    try {
                        gateway.ack(listOf(envelope.id))
                    } catch (e: Exception) {
                        log("envelope ack failed: ${e.message}")
                        logger.ackFailed(envelopeId, e.message ?: "unknown")
                    }
                } catch (e: Exception) {
                    // Decrypt failed or persistence threw. The message is not
                    // durably stored, so leave it unacked for reconnect redelivery.
                    log("RECV FAIL sender=$senderId err=${e.message?.take(60)} (left on server, not acked)")
                    trackDecryptFailure(senderId)
                    logger.decryptFailed(senderId, e.message ?: "unknown")
                }
            }
        }
    }

    private fun isDecryptRateLimited(senderId: String): Boolean {
        val (count, windowStart) = decryptFailures[senderId] ?: return false
        val now = System.currentTimeMillis()
        if (now - windowStart > DECRYPT_FAILURE_WINDOW_MS) {
            decryptFailures.remove(senderId)
            return false
        }
        return count >= MAX_DECRYPT_FAILURES
    }

    private fun trackDecryptFailure(senderId: String) {
        val now = System.currentTimeMillis()
        val (count, windowStart) = decryptFailures[senderId] ?: Pair(0, now)
        if (now - windowStart > DECRYPT_FAILURE_WINDOW_MS) {
            decryptFailures[senderId] = Pair(1, now)
        } else {
            decryptFailures[senderId] = Pair(count + 1, windowStart)
        }
    }

    /**
     * Persist a decrypted message, by class (`KIT_API.md` §4).
     *
     * Called from the envelope loop **before** the ack, and it throws on a failed durable write so
     * the ack is skipped and the message survives on the server (SPEC §0.9 rule 3).
     *
     * [classify] decides what each arm may do; this method performs the corresponding handler.
     *
     * Returns false when this envelope was a REDELIVERY the inbox absorbed, so the caller can skip
     * the wake-up emits. It still gets acked — see [inboxMessage].
     */
    internal suspend fun routeMessage(
        msg: ClientMessage,
        sourceUserId: String,
        senderDeviceId: String?,
        envelopeId: String,
    ): Boolean {
        when (payloadDisposition(msg.payloadCase)) {
            PayloadDisposition.INBOXED -> return inboxMessage(msg, sourceUserId, senderDeviceId, envelopeId)

            PayloadDisposition.DROPPABLE, PayloadDisposition.KIT_INTERNAL -> when (msg.payloadCase) {
                ClientMessage.PayloadCase.FRIEND_REQUEST -> handleFriendRequest(msg, sourceUserId)
                ClientMessage.PayloadCase.FRIEND_ACCEPT -> handleFriendAccept(sourceUserId)
                ClientMessage.PayloadCase.DEVICE_ANNOUNCE -> handleDeviceAnnounce(msg, sourceUserId)
                ClientMessage.PayloadCase.DEVICE_LINK_APPROVAL -> handleLinkApproval(msg, sourceUserId)
                ClientMessage.PayloadCase.TYPING_SIGNAL ->
                    handleTypingSignal(msg, sourceUserId, senderDeviceId)
                else -> error("classified ${msg.payloadCase.name} as kit-internal with no handler")
            }
        }
        return true
    }

    /**
     * Write an inboxed payload to the durable inbox.
     *
     * If the write throws, nothing is acked and the message stays on the server — that is the whole
     * point of persist-then-ack and the reason this is not an event stream.
     *
     * Returns **false when the row was already there**. The caller still acknowledges the
     * redelivery but skips wake-up events so the app cannot post a duplicate notification.
     */
    internal suspend fun inboxMessage(
        msg: ClientMessage,
        sourceUserId: String,
        senderDeviceId: String?,
        envelopeId: String,
    ): Boolean {
        val isAppEntry = msg.payloadCase == ClientMessage.PayloadCase.APP_ENTRY
        val sync = msg.appEntry

        val inserted = inbox.put(
            InboxInsert(
                envelopeId = envelopeId,
                // Must match Swift byte for byte — the app reads one `kind` column from two
                // kits, and §4.1 has pix's drain BRANCH on it (an unrecognised kind is discarded).
                // `payloadCase.name` gives Kotlin's "PAYLOAD_NOT_SET" where Swift's WireCodec gives
                // "", so a drain keying on one silently fails on the other platform: rows pile up,
                // depth never returns to zero, and with the `after:` cursor deferred the head of the
                // queue wedges. Both kits now go through WireCodec and share the UNKNOWN sentinel —
                // an empty string is a poor value for a NOT NULL column read across a bridge.
                kind = WireCodec.decodeType(msg.payloadCase).ifEmpty { "UNKNOWN" },
                senderUserId = sourceUserId,
                senderDeviceId = senderDeviceId,
                // AppEntry-derived, so null for an unknown arm — there is nothing to derive from.
                modelKey = if (isAppEntry) sync.model else null,
                entryId = if (isAppEntry) sync.id else null,
                sentAt = if (isAppEntry) clampFutureTimestamp(sync.timestamp) else null,
                // Opaque bytes. For an unknown arm this is the whole serialized message, because the
                // kit cannot know which sub-field would have been the payload.
                payload = if (isAppEntry) sync.data.toByteArray() else msg.toByteArray(),
            )
        )

        if (!inserted) {
            // A redelivered envelope. Not an error: persist-then-ack guarantees this happens, and
            // absorbing it here is what keeps depth() and the app's counts honest. Still acked by
            // the caller; just not announced a second time.
            log("RECV DUPLICATE envelope=${envelopeId.take(12)} kind=${msg.payloadCase.name} (already inboxed)")
            return false
        }

        return true
    }

    /**
     * SPEC §2.4: a peer-supplied timestamp is clamped before it is stored, not after.
     *
     * Without this a peer can set `sentAt` far in the future and win every REPLACE conflict forever
     * — the tie-break can only order writes it can compare honestly.
     */
    internal fun clampFutureTimestamp(sentAt: Long): Long {
        val cap = System.currentTimeMillis() + 60_000L
        // `AppEntry.timestamp` is proto3 `uint64`, which protobuf-java surfaces as a SIGNED Long —
        // so a peer sending >= 2^63 arrives here NEGATIVE and sails under any `minOf` cap. Swift
        // does the same comparison in UInt64 space and correctly yields the cap, so the unguarded
        // version stored roughly -9.2e18 on Android and now+60s on iOS for identical wire bytes.
        // Clamping both ends keeps §2.4 honest and the two kits in agreement.
        if (sentAt < 0) return cap
        return minOf(sentAt, cap)
    }

    private suspend fun handleFriendRequest(msg: ClientMessage, sourceUserId: String) {
        // sourceUserId is envelope.sender_id — the requester's USER id, server-stamped and
        // authenticated by the Signal session that decrypted this message (TOFU: libsignal pins the
        // sender's identity key on first contact, exactly as Signal does).
        //
        // The payload username is a first-contact label only (SPEC §0.10 rule 5). A known peer
        // cannot use another request to replace its locally trusted name or friendship status.
        val existing = friends.get(sourceUserId)
        if (existing != null) {
            if (existing.status == FriendStatus.PENDING_SENT) {
                // Crossed requests are mutual consent. Accepting here prevents both peers from
                // remaining permanently pending when they scan each other's codes.
                friendshipManager.acceptFriend(sourceUserId)
                logger.log("crossed friend request from $sourceUserId promoted to accepted")
                return
            }
            // Already known. The name now comes from our graph, never from their payload. Refresh
            // the device list (that IS ours to learn) and change nothing else.
            friends.updateDevices(sourceUserId, messenger.knownDevicesFor(sourceUserId))
            logger.log(
                "friend request from already-known peer $sourceUserId (status=${existing.status.value}); " +
                    "keeping stored name and status"
            )
            return
        }
        // Genuine first contact: the payload username is the only name that exists.
        friends.add(sourceUserId, msg.friendRequest.username, FriendStatus.PENDING_RECEIVED,
            messenger.knownDevicesFor(sourceUserId))
    }

    private suspend fun handleFriendAccept(sourceUserId: String) {
        // A response is valid only for a request we sent. Delivery alone must not allow an
        // authenticated stranger to insert itself as an accepted friend.
        val existing = friends.get(sourceUserId)
        if (existing == null || existing.status != FriendStatus.PENDING_SENT) {
            logger.log(
                "ignoring unsolicited FRIEND_ACCEPT from $sourceUserId " +
                    "(local status=${existing?.status?.value ?: "none"}; expected pending_sent)"
            )
            return
        }

        // Promote in place. The name stays the one WE recorded when we sent the request; the
        // payload's username is not consulted (SPEC §0.5 — the graph names the peer, not the peer).
        friends.updateStatus(sourceUserId, FriendStatus.ACCEPTED)
        friends.updateDevices(sourceUserId, messenger.knownDevicesFor(sourceUserId))
    }

    internal suspend fun handleDeviceAnnounce(msg: ClientMessage, sourceUserId: String) {
        val announce = msg.deviceAnnounce
        friends.updateDevices(
            sourceUserId,
            announce.devicesList.map { d -> FriendDeviceInfo(d.id, d.name) },
            clampFutureTimestamp(msg.timestamp),
        )
    }

    internal suspend fun handleTypingSignal(
        msg: ClientMessage,
        sourceUserId: String,
        senderDeviceId: String?,
    ) {
        try {
            val signal = msg.typingSignal
            if (signal.contextId.isBlank()) return
            val deviceId = senderDeviceId ?: run {
                log("TYPING DROPPED: authenticated sender device is missing")
                return
            }
            when (WireCodec.decodeTypingState(signal.state)) {
                TypingState.STARTED -> typingTracker.receive(
                    contextId = signal.contextId,
                    senderUserId = sourceUserId,
                    senderDeviceId = deviceId,
                    senderDisplayName = friends.get(sourceUserId)?.username ?: sourceUserId,
                )
                TypingState.STOPPED -> typingTracker.clear(signal.contextId, deviceId)
                null -> log("TYPING DROPPED: unspecified or unknown state")
            }
        } catch (e: Exception) {
            log("typing signal handling failed: ${e.message}")
        }
    }

    private suspend fun handleLinkApproval(msg: ClientMessage, sourceUserId: String) {
        // Only accept approval from our own account
        if (sourceUserId != userId) return
        // Only process if we're actually waiting for approval
        if (_authState.value != AuthState.PENDING_APPROVAL) return

        val approval = msg.deviceLinkApproval

        // Verify challenge matches the one we generated in our link code
        val pendingChallenge = session.pendingLinkChallenge
        if (pendingChallenge != null && approval.challengeResponse.size() > 0) {
            val received = approval.challengeResponse.toByteArray()
            if (!LinkCode.verifyChallenge(pendingChallenge, received)) {
                logger.decryptFailed(sourceUserId, "Link approval challenge mismatch")
                return
            }
        }

        // Import device list from approval
        val approvedDevices = approval.ownDevicesList.map { d ->
            FriendDeviceInfo(d.id, d.name)
        }
        if (approvedDevices.isNotEmpty()) {
            devices.setOwnDevices(approvedDevices)
        }

        // Import friend data from approval
        if (approval.friendsExport.size() > 0) {
            try {
                friends.importAll(String(approval.friendsExport.toByteArray()))
            } catch (e: Exception) { log("friend import from link approval failed: ${e.message}") }
        }

        session.pendingLinkChallenge = null
        _authState.value = AuthState.AUTHENTICATED
    }

    // Attachment convenience methods resolve friend usernames. App entry sends use explicit
    // recipient user ids through `send` (SPEC §0.4).

    /**
     * Send an application entry (`KIT_API.md` §5) — the outbox half of the thin kit,
     * paired with [inbox] on the receive side and [entries] for local storage.
     *
     * **The caller names the recipients.** The kit fans out to every device of every listed userId
     * plus this user's own *other* devices, and resolves no audience of its own (SPEC §0.4). The
     * sender receives no inbox row, so the app writes its own outgoing entry to [entries].
     */
    suspend fun send(
        recipientUserIds: List<String>,
        modelKey: String,
        entryId: String,
        sentAt: Long = System.currentTimeMillis(),
        payload: ByteArray,
    ) = contentService.sendEntry(recipientUserIds, modelKey, entryId, sentAt, payload)

    suspend fun sendTyping(
        recipientUserIds: List<String>,
        contextId: String,
        state: TypingState,
    ) {
        require(contextId.isNotBlank()) { "contextId must not be blank" }
        val ownDeviceId = deviceId ?: throw ObscuraError.NotAuthenticated()
        if (!typingTracker.shouldSend(contextId, state, ownDeviceId)) return

        val message = ClientMessage.newBuilder()
            .setTimestamp(System.currentTimeMillis())
            .setTypingSignal(typingSignal {
                this.contextId = contextId
                this.state = WireCodec.encodeTypingState(state)
            })
            .build()
        for (recipientUserId in recipientUserIds.distinct().filter { it != userId }) {
            messageSender.sendToAllDevices(recipientUserId, message)
        }
    }

    /**
     * Who is currently typing in a context, by display name.
     *
     * Auto-expires; a signal with no refresh disappears on its own, which is what makes signals
     * droppable (`KIT_API.md` §4) rather than something the inbox has to carry.
     */
    fun observeTyping(contextId: String): Flow<List<String>> = typingTracker.observe(contextId)

    suspend fun uploadAttachment(plaintext: ByteArray): AttachmentUpload = contentService.uploadAttachment(plaintext)
    suspend fun downloadDecryptedAttachment(id: String, contentKey: ByteArray, nonce: ByteArray): ByteArray =
        contentService.downloadDecryptedAttachment(id, contentKey, nonce)

    suspend fun befriend(targetUserId: String, targetUsername: String) = friendshipManager.befriend(targetUserId, targetUsername)
    suspend fun acceptFriend(targetUserId: String) = friendshipManager.acceptFriend(targetUserId)

    suspend fun announceDevices() = deviceManager.announceDevices()
    /**
     * Generate a link code for this device. Display as QR code or copyable text.
     * The existing device scans this and calls validateAndApproveLink().
     */
    fun generateLinkCode(): String {
        val did = deviceId ?: throw ObscuraError.NotProvisioned("Not provisioned — call loginAndProvision first")
        val generated = LinkCode.generate(did)
        session.pendingLinkChallenge = generated.challenge
        return generated.code
    }

    /**
     * Validate a link code and approve the new device. Called by the EXISTING device
     * after scanning QR or receiving pasted code from the new device.
     */
    suspend fun validateAndApproveLink(linkCode: String) {
        val result = LinkCode.validate(linkCode)
        require(result.valid) { result.error ?: "Invalid link code" }
        val data = result.data!!
        deviceManager.approveLink(data.deviceId, data.challenge)
    }

    /**
     * Low-level approve — use validateAndApproveLink() instead for the full flow.
     */
    suspend fun approveLink(newDeviceId: String, challengeResponse: ByteArray) =
        deviceManager.approveLink(newDeviceId, challengeResponse)
    suspend fun takeoverDevice() = deviceManager.takeoverDevice()

    companion object {
        // See [decryptFailures].
        private const val MAX_DECRYPT_FAILURES = 10
        private const val DECRYPT_FAILURE_WINDOW_MS = 60_000L

        // Keep the single reconnect retry inside the push path's tight OS time budget.
        private const val PUSH_DRAIN_RECONNECT_RETRY_MS = 250L

        /** See [shouldForceReconnectAfterPush]. */
        private const val PUSH_DRAIN_RECENT_ACTIVITY_MS = 10_000L

        /** See [awaitEnvelopes]. */
        private const val PUSH_DRAIN_IDLE_THRESHOLD_MS = 500L

        // Prekey replenishment
        private const val PREKEY_MIN_COUNT = 20L
        private const val PREKEY_REPLENISH_COUNT = 50
    }
}

/**
 * Should a push-wake drain that came up empty tear down the socket and try again?
 *
 * A silent push is not a generic "wake up and look around". The server schedules it when it accepts
 * a message and cancels it once this device acks, so a push landing here means the server had
 * something for us and did not see our ack — evidence about the connection, arriving over a
 * different channel than the connection.
 *
 * So when [startedConnected] is true and [processed] is still zero, the local belief "I am connected
 * and receiving" has been CONTRADICTED. Acting on that is error handling rather than distrust of
 * state, and it is self-limiting: reconnects are capped by pushes, which are capped by messages that
 * failed to deliver — zero in a healthy system.
 *
 * [recentActivityWindowMs] covers the one benign way to reach zero: the socket delivered the message
 * just before the scheduled push fired, with our ack still in flight. Reconnecting there is churn.
 */
internal fun shouldForceReconnectAfterPush(
    processed: Long,
    startedConnected: Boolean,
    lastProcessedAtMs: Long,
    nowMs: Long,
    recentActivityWindowMs: Long,
): Boolean {
    if (processed > 0L) return false

    // A socket established for this drain has no staleness to blame, and reconnecting it again
    // would change nothing.
    if (!startedConnected) return false

    // Never delivered anything, so it gets no benefit of the doubt.
    if (lastProcessedAtMs == 0L) return true

    return nowMs - lastProcessedAtMs > recentActivityWindowMs
}

/**
 * Row-to-[FriendData] projection for [ObscuraClient.friendList].
 *
 * Kept as a small explicit projection so the observed facade does not depend on generated
 * SQLDelight row types.
 */
private fun Friend.toObservedFriendData(): FriendData {
    return FriendData(
        userId = user_id,
        username = username,
        status = FriendStatus.entries.find { it.value == status } ?: FriendStatus.PENDING_SENT,
        devices = parseFriendDevices(devices),
    )
}
