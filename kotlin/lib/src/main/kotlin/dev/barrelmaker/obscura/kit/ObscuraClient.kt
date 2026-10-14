package dev.barrelmaker.obscura.kit

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.protobuf.ByteString
import dev.barrelmaker.obscura.kit.crypto.AttachmentCrypto
import dev.barrelmaker.obscura.kit.crypto.SignalStore
import dev.barrelmaker.obscura.kit.crypto.UuidCodec
import dev.barrelmaker.obscura.kit.db.ObscuraDatabase
import dev.barrelmaker.obscura.kit.managers.AuthManager
import dev.barrelmaker.obscura.kit.managers.Credentials
import dev.barrelmaker.obscura.kit.managers.MessageSender
import dev.barrelmaker.obscura.kit.managers.SignalKeyUtils
import dev.barrelmaker.obscura.kit.managers.TokenStatus
import dev.barrelmaker.obscura.kit.messaging.Messenger
import dev.barrelmaker.obscura.kit.network.APIClient
import dev.barrelmaker.obscura.kit.network.GatewayConnection
import dev.barrelmaker.obscura.kit.persistence.SessionStorage
import dev.barrelmaker.obscura.kit.stores.DeviceStore
import dev.barrelmaker.obscura.kit.stores.EntryStore
import dev.barrelmaker.obscura.kit.stores.FriendData
import dev.barrelmaker.obscura.kit.stores.FriendStatus
import dev.barrelmaker.obscura.kit.stores.FriendStore
import dev.barrelmaker.obscura.kit.stores.InboxInsert
import dev.barrelmaker.obscura.kit.stores.InboxStore
import dev.barrelmaker.obscura.kit.stores.pragma
import dev.barrelmaker.obscura.kit.stores.truncateWal
import dev.barrelmaker.obscura.kit.wire.PayloadDisposition
import dev.barrelmaker.obscura.kit.wire.TypingTracker
import dev.barrelmaker.obscura.kit.wire.WireCodec
import dev.barrelmaker.obscura.kit.wire.payloadDisposition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import obscura.client.v1.Client.ClientMessage
import obscura.client.v1.appEntry
import obscura.client.v1.friendAccept
import obscura.client.v1.friendRequest
import obscura.client.v1.typingSignal
import xyz.obscura.server.contracts.ObscuraProtocol.Envelope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * The kit. With [driver] null the kit opens [ObscuraConfig.databasePath] (or an in-memory database)
 * and creates the schema; an app-supplied driver must already have it.
 */
class ObscuraClient(
    config: ObscuraConfig,
    driver: SqlDriver?,
    sessionStorage: SessionStorage,
) {
    var logger: ObscuraLogger = NoOpLogger

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val sqlDriver: SqlDriver
    internal val db: ObscuraDatabase

    init {
        if (driver != null && config.databasePath != null) throw ObscuraError.InvalidArgument("Pass either a driver or a databasePath, not both")
        sqlDriver = driver ?: JdbcSqliteDriver(config.databasePath?.let { "jdbc:sqlite:$it" } ?: JdbcSqliteDriver.IN_MEMORY)
            .also { ObscuraDatabase.Schema.create(it) }
        // EntryStore.erase relies on freed pages being zeroed.
        sqlDriver.pragma("PRAGMA secure_delete = ON")
        db = ObscuraDatabase(sqlDriver)
    }

    internal val signalStore = SignalStore(db).apply { onIdentityChanged = { logger.identityChanged(it) } }
    private val friends = FriendStore(db)
    internal val devices = DeviceStore(db)

    val inbox = InboxStore(db) { ids, reason -> log("INBOX discarded ${ids.size} row(s) ($reason): $ids") }
    val entries = EntryStore(db, sqlDriver)

    internal val auth: AuthManager
    internal val api: APIClient
    private val gateway: GatewayConnection
    private val messenger: Messenger
    internal val sender: MessageSender
    private val typing = TypingTracker(scope)

    init {
        lateinit var authRef: AuthManager
        api = APIClient(config.apiUrl) { authRef.credentials?.token }
        auth = AuthManager(
            api, signalStore, devices, sessionStorage, config.deviceName, scope,
            logger = { logger },
            onRejected = { credentials, generation -> endSession(revoke = false, expected = credentials, expectedGeneration = generation) },
        )
        authRef = auth
        gateway = GatewayConnection(api, scope, beforeConnect = {
            val self = requireFreshToken()
            refreshOwnDirectory(self)
        }, log = ::log)
        messenger = Messenger(signalStore, api)
        sender = MessageSender(api, messenger, devices) { auth.credentials?.deviceId }
    }

    val authState: StateFlow<AuthState> get() = auth.state
    val connectionState: StateFlow<ConnectionState> get() = gateway.state

    val userId: String? get() = auth.credentials?.userId
    val deviceId: String? get() = auth.credentials?.deviceId
    val username: String? get() = auth.credentials?.username

    /** Payload-free; emits on collection and on every friend change. Call [getFriends] for the rows. */
    val friendsChanged: Flow<Unit> = friends.changes

    private val newEntries = Channel<String>(MESSAGE_RECEIVED_CAPACITY, BufferOverflow.DROP_OLDEST)

    /** The model key of each newly inboxed APP_ENTRY row. One consumer. The inbox is the delivery path. */
    val messageReceived: ReceiveChannel<String> = newEntries

    private val debugLog = ConcurrentLinkedDeque<String>()

    /** The last [DEBUG_LOG_SIZE] log lines, newest first. */
    fun getDebugLog(): List<String> = debugLog.toList()

    suspend fun register(username: String, password: String) = auth.register(username, password)

    suspend fun login(username: String, password: String): LoginScenario = auth.login(username, password)

    suspend fun loginAndProvision(username: String, password: String) = auth.loginAndProvision(username, password)

    /**
     * Restore the stored session. True when restored; the client then connects if it can and stays
     * authenticated but disconnected if it cannot. Never throws.
     */
    suspend fun restorePersistedSession(): Boolean {
        try {
            if (!auth.restore()) return false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("SESSION restore failed: ${e.message}")
            return false
        }
        try {
            connect()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("SESSION restored offline: ${e.message}")
        }
        return true
    }

    /** Ends the session and revokes it on the server. Local data is kept. */
    suspend fun logout() = endSession(revoke = true)

    /** [logout], then delete every piece of local kit data. */
    suspend fun wipeDevice() {
        logout()
        withContext(Dispatchers.IO) {
            db.transaction {
                sqlDriver.pragma("PRAGMA secure_delete = ON")
                db.friendQueries.deleteAll()
                db.deviceQueries.deleteAll()
                db.deviceQueries.deleteIdentity()
                db.modelEntryQueries.deleteAllEntries()
                db.inboxQueries.deleteAll()
                signalStore.clear()
            }
            sqlDriver.truncateWal()
        }
    }

    private suspend fun endSession(revoke: Boolean, expected: Credentials? = null, expectedGeneration: Long? = null) {
        val previous = try {
            auth.end(expected, expectedGeneration)
        } finally {
            if (auth.credentials == null) {
                gateway.disconnect()
                val stopping = synchronized(this) {
                    val jobs = listOfNotNull(receiveJob, replenishJob)
                    jobs.forEach { it.cancel() }
                    receiveJob = null
                    replenishJob = null
                    jobs
                }
                withContext(NonCancellable) { stopping.forEach { it.join() } }
                typing.clear()
            }
        }
        if (expected != null && previous == null) return
        if (revoke && previous != null) auth.revoke(previous)
        log("SESSION ended")
    }

    private suspend fun requireFreshToken(): Credentials {
        if (auth.ensureFreshToken() == TokenStatus.REJECTED) throw ObscuraError.NotAuthenticated()
        return auth.requireCredentials()
    }

    /** Returns once the socket is open. Idempotent; joins an in-flight connect. */
    suspend fun connect() {
        auth.requireCredentials()
        startReceiving()
        gateway.connect()
        log("CONNECTED")
    }

    /** Closes the socket and stops reconnection. */
    fun disconnect() {
        gateway.disconnect()
        log("DISCONNECTED")
    }

    private var receiveJob: Job? = null
    private var replenishJob: Job? = null
    private val processedEnvelopes = MutableStateFlow(0L)
    @Volatile private var lastProcessedAtMs = 0L

    private fun startReceiving() = synchronized(this) {
        if (receiveJob?.isActive == true) return
        receiveJob = scope.launch {
            launch { for (envelope in gateway.envelopes) receive(envelope) }
            launch {
                for (status in gateway.preKeyStatus) {
                    if (status.oneTimePreKeyCount < status.minThreshold) replenishPreKeys { true }
                }
            }
        }
    }

    /** Commit receive ratchets with local durable writes; network-dependent control handlers run afterward. */
    internal suspend fun receive(envelope: Envelope, ack: suspend (ByteString) -> Unit = { gateway.ack(it) }) {
        val envelopeId = UuidCodec.uuidString(envelope.id)
        val senderUserId = UuidCodec.uuidString(envelope.senderId)
        val senderDeviceId = UuidCodec.uuidString(envelope.senderDeviceId)
        if (envelopeId == null || senderUserId == null || senderDeviceId == null) {
            log("RECV malformed envelope (id, sender or sender device is not 16 bytes); not acked")
            return
        }
        try {
            if (inbox.contains(envelopeId)) {
                ack(envelope.id)
                markProcessed()
                return
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("RECV duplicate lookup or ack failed for $envelopeId: ${e.message}")
            return
        }
        var stage = "decrypt"
        val (message, inserted, handledLocally) = try {
            messenger.decryptAndCommit(senderDeviceId, envelope.message) { plaintext ->
                stage = "directory"
                devices.addInTransaction(senderUserId, senderDeviceId)
                val parsed = try {
                    ClientMessage.parseFrom(plaintext)
                } catch (e: com.google.protobuf.InvalidProtocolBufferException) {
                    log("RECV invalid client message from $senderUserId: ${e.message}; not acked")
                    return@decryptAndCommit Triple(null, false, false)
                }
                stage = "persist"
                val inserted = if (payloadDisposition(parsed.payloadCase) == PayloadDisposition.INBOXED) {
                    inbox.putInTransaction(inboxRecord(parsed, senderUserId, senderDeviceId, envelopeId))
                } else false
                val handledLocally = payloadDisposition(parsed.payloadCase) == PayloadDisposition.KIT_INTERNAL &&
                    handleLocalFriendMessage(parsed, senderUserId)
                Triple(parsed, inserted, handledLocally)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (stage == "decrypt") {
                log("RECV decrypt failed from $senderUserId: ${e.message}")
                logger.decryptFailed(senderUserId, e.message ?: e.javaClass.simpleName)
            } else {
                log("RECV $stage not stored: ${e.message}")
                logger.databaseError(stage, "receive", e.message ?: e.javaClass.simpleName)
            }
            return
        }
        if (message == null) return
        try {
            if (payloadDisposition(message.payloadCase) == PayloadDisposition.INBOXED) {
                wakeInbox(message, envelopeId, inserted)
            } else if (!handledLocally) {
                route(message, senderUserId, senderDeviceId, envelopeId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("RECV ${message.payloadCase.name} not stored: ${e.message}")
            logger.databaseError("receive", message.payloadCase.name, e.message ?: e.javaClass.simpleName)
            return
        }
        replenishPreKeys { signalStore.getPreKeyCount() < PREKEY_MIN_COUNT }
        try {
            ack(envelope.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("RECV ack failed for $envelopeId: ${e.message}")
            logger.ackFailed(envelopeId, e.message ?: e.javaClass.simpleName)
        }
        markProcessed()
    }

    private fun markProcessed() {
        lastProcessedAtMs = System.currentTimeMillis()
        processedEnvelopes.value++
    }

    /** Store or apply a decrypted message. Throws when it could not be stored. */
    internal suspend fun route(message: ClientMessage, senderUserId: String, senderDeviceId: String, envelopeId: String) {
        when (payloadDisposition(message.payloadCase)) {
            PayloadDisposition.INBOXED -> inboxMessage(message, senderUserId, senderDeviceId, envelopeId)
            PayloadDisposition.KIT_INTERNAL ->
                if (message.payloadCase == ClientMessage.PayloadCase.FRIEND_REQUEST) onFriendRequest(message, senderUserId)
                else onFriendAccept(senderUserId)
            PayloadDisposition.DROPPABLE -> onTypingSignal(message, senderUserId, senderDeviceId)
        }
    }

    private suspend fun inboxMessage(message: ClientMessage, senderUserId: String, senderDeviceId: String, envelopeId: String) {
        val inserted = inbox.put(inboxRecord(message, senderUserId, senderDeviceId, envelopeId))
        wakeInbox(message, envelopeId, inserted)
    }

    private fun inboxRecord(message: ClientMessage, senderUserId: String, senderDeviceId: String, envelopeId: String): InboxInsert {
        val entry = message.takeIf { it.payloadCase == ClientMessage.PayloadCase.APP_ENTRY }?.appEntry
        return InboxInsert(
            envelopeId = envelopeId,
            kind = WireCodec.kind(message.payloadCase),
            senderUserId = senderUserId,
            senderDeviceId = senderDeviceId,
            modelKey = entry?.model,
            entryId = entry?.id,
            sentAt = entry?.let { clampFutureTimestamp(it.timestamp) },
            // An unknown arm is stored whole: the kit cannot know which field is the payload.
            payload = entry?.data?.toByteArray() ?: message.toByteArray(),
        )
    }

    private fun wakeInbox(message: ClientMessage, envelopeId: String, inserted: Boolean) {
        when {
            !inserted -> log("RECV duplicate envelope $envelopeId")
            message.payloadCase == ClientMessage.PayloadCase.APP_ENTRY -> newEntries.trySend(message.appEntry.model)
        }
    }

    private suspend fun onFriendRequest(message: ClientMessage, senderUserId: String) {
        val existing = friends.get(senderUserId)
        when (existing?.status) {
            null -> friends.add(senderUserId, message.friendRequest.username, FriendStatus.PENDING_RECEIVED)
            // Crossed requests are mutual consent.
            FriendStatus.PENDING_SENT -> acceptFriend(senderUserId)
            FriendStatus.ACCEPTED -> {
                requireFreshToken()
                sendFriendAccept(senderUserId)
                log("FRIEND_REQUEST from known peer $senderUserId (${existing.status.value}); unchanged, acceptance replied")
            }
            else -> log("FRIEND_REQUEST from known peer $senderUserId (${existing.status.value}); unchanged")
        }
    }

    private suspend fun onFriendAccept(senderUserId: String) {
        if (friends.get(senderUserId)?.status == FriendStatus.PENDING_SENT) {
            friends.updateStatus(senderUserId, FriendStatus.ACCEPTED)
        } else {
            log("FRIEND_ACCEPT from $senderUserId without a pending request; ignored")
        }
    }

    /** Only local transitions belong in the receive transaction; acceptance replies require HTTP. */
    private fun handleLocalFriendMessage(message: ClientMessage, senderUserId: String): Boolean =
        when (message.payloadCase) {
            ClientMessage.PayloadCase.FRIEND_REQUEST ->
                if (friends.getInTransaction(senderUserId) == null) {
                    friends.addInTransaction(senderUserId, message.friendRequest.username, FriendStatus.PENDING_RECEIVED)
                    true
                } else false
            ClientMessage.PayloadCase.FRIEND_ACCEPT -> {
                if (friends.getInTransaction(senderUserId)?.status == FriendStatus.PENDING_SENT) {
                    friends.updateStatusInTransaction(senderUserId, FriendStatus.ACCEPTED)
                } else {
                    log("FRIEND_ACCEPT from $senderUserId without a pending request; ignored")
                }
                true
            }
            else -> false
        }

    private fun onTypingSignal(message: ClientMessage, senderUserId: String, senderDeviceId: String) {
        val signal = message.typingSignal
        if (signal.contextId.isBlank()) return
        when (WireCodec.decodeTypingState(signal.state)) {
            TypingState.STARTED -> typing.started(signal.contextId, senderUserId, senderDeviceId)
            TypingState.STOPPED -> typing.stopped(signal.contextId, senderDeviceId)
            null -> log("TYPING with unspecified state from $senderUserId; ignored")
        }
    }

    /** At most one replenish in flight; a backlog of envelopes would otherwise upload the same ids N times. */
    private fun replenishPreKeys(needed: () -> Boolean) = synchronized(this) {
        if (auth.credentials == null || replenishJob?.isActive == true) return
        replenishJob = scope.launch {
            try {
                if (needed()) {
                    requireFreshToken()
                    val keys = SignalKeyUtils.generateMorePreKeys(signalStore, PREKEY_REPLENISH_COUNT)
                    api.uploadDeviceKeys(keys)
                    signalStore.retireSignedPreKeys(keys.signedPreKey.keyId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.preKeyReplenishFailed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private val drainMutex = Mutex()

    /**
     * After a push wake: connect, wait until the receive path has been idle for 500 ms or
     * [timeoutMs] passes, and return how many envelopes were processed. Stays connected.
     */
    suspend fun processPendingMessages(timeoutMs: Long): Int = drainMutex.withLock {
        val start = processedEnvelopes.value
        val deadline = System.currentTimeMillis() + timeoutMs
        val startedConnected = connectionState.value == ConnectionState.CONNECTED
        if (!startedConnected && !tryConnect()) return 0
        awaitIdle(deadline)
        // A push means the server holds something this device has not acked, so an empty drain on a
        // socket that looked connected and has been quiet means the socket is dead.
        if (startedConnected && processedEnvelopes.value == start &&
            System.currentTimeMillis() - lastProcessedAtMs > PUSH_RECENT_ACTIVITY_MS
        ) {
            log("PUSH nothing drained on a connected socket; reconnecting")
            gateway.disconnect()
            if (tryConnect()) awaitIdle(deadline)
        }
        (processedEnvelopes.value - start).toInt()
    }

    private suspend fun tryConnect(): Boolean = try {
        connect()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("PUSH connect failed: ${e.message}")
        false
    }

    private suspend fun awaitIdle(deadline: Long) {
        var seen = processedEnvelopes.value
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return
            seen = withTimeoutOrNull(minOf(PUSH_IDLE_MS, remaining)) { processedEnvelopes.first { it > seen } } ?: return
        }
    }

    suspend fun registerPushToken(token: String) {
        requireFreshToken()
        api.registerPushToken(token)
    }

    /**
     * Send an entry to every device of [recipientUserIds] and to this user's other devices. Devices
     * in [acceptedDeviceIds] are reported accepted without a resend. No inbox row is written here;
     * the app stores its own copy in [entries].
     */
    suspend fun send(
        recipientUserIds: List<String>,
        modelKey: String,
        entryId: String,
        sentAt: Long,
        payload: ByteArray,
        acceptedDeviceIds: Set<String>,
    ): SendReport {
        val self = requireFreshToken()
        val message = clientMessage {
            setAppEntry(appEntry {
                model = modelKey
                id = entryId
                timestamp = sentAt
                data = ByteString.copyFrom(payload)
            })
        }
        val recipients = recipientUserIds.distinct().filter { it != self.userId }
            .flatMap { sender.send(it, message, acceptedDeviceIds) }
        val ownIds = try {
            fetchOwnDirectory()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SendReport(recipients, listOf(SubmissionResult(self.userId, null, SubmissionStatus.UNKNOWN, e.message ?: e.javaClass.simpleName)))
        }
        val selfSync = try {
            storeOwnDirectory(self, ownIds)
            sender.send(self.userId, message, acceptedDeviceIds)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            listOf(SubmissionResult(self.userId, null, SubmissionStatus.REJECTED, e.message ?: e.javaClass.simpleName))
        }
        return SendReport(recipients, selfSync)
    }

    /** Throttled per (context, state). A recipient that cannot be reached is logged and skipped. */
    suspend fun sendTyping(recipientUserIds: List<String>, contextId: String, state: TypingState) {
        if (contextId.isBlank()) throw ObscuraError.InvalidArgument("contextId must not be blank")
        val self = auth.requireCredentials()
        if (!typing.shouldSend(contextId, state)) return
        requireFreshToken()
        val message = clientMessage {
            setTypingSignal(typingSignal {
                this.contextId = contextId
                this.state = WireCodec.encodeTypingState(state)
            })
        }
        for (recipient in recipientUserIds.distinct().filter { it != self.userId }) {
            try {
                sender.sendOrThrow(recipient, message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("TYPING not delivered to $recipient: ${e.message}")
            }
        }
    }

    /** The ids of users typing in [contextId], sorted. Each expires 5 s after its last signal. */
    fun observeTyping(contextId: String): Flow<List<String>> {
        if (contextId.isBlank()) throw ObscuraError.InvalidArgument("contextId must not be blank")
        return typing.observe(contextId)
    }

    suspend fun uploadAttachment(plaintext: ByteArray): AttachmentUpload {
        requireFreshToken()
        val encrypted = AttachmentCrypto.encrypt(plaintext)
        return AttachmentUpload(api.uploadAttachment(encrypted.ciphertext), encrypted.contentKey, encrypted.nonce)
    }

    suspend fun downloadDecryptedAttachment(id: String, contentKey: ByteArray, nonce: ByteArray): ByteArray {
        requireFreshToken()
        return AttachmentCrypto.decrypt(api.fetchAttachment(id), contentKey, nonce)
    }

    fun friendCode(): String {
        val self = auth.requireCredentials()
        return FriendCode.encode(self.userId, self.username)
    }

    /** Whitespace and soft hyphens (added by share sheets) are stripped before decoding. */
    suspend fun addFriendByCode(code: String) {
        val decoded = FriendCode.decode(code.filterNot { it.isWhitespace() || it == '­' })
        befriend(decoded.userId, decoded.username)
    }

    internal suspend fun befriend(targetUserId: String, targetUsername: String) {
        val self = requireFreshToken()
        if (targetUserId == self.userId) throw ObscuraError.InvalidArgument("Cannot befriend yourself")
        val existing = friends.prepareRequest(targetUserId, targetUsername)
        when (existing?.status) {
            FriendStatus.ACCEPTED -> return
            FriendStatus.PENDING_RECEIVED -> return acceptFriend(targetUserId)
            FriendStatus.PENDING_SENT, null -> Unit
        }
        try {
            sender.sendOrThrow(targetUserId, clientMessage { setFriendRequest(friendRequest { username = self.username }) })
        } catch (e: Exception) {
            if (existing == null) withContext(NonCancellable) { friends.removePendingRequest(targetUserId) }
            throw e
        }
    }

    suspend fun acceptFriend(userId: String) {
        requireFreshToken()
        if (friends.get(userId) == null) throw ObscuraError.InvalidArgument("No friend request from $userId")
        sendFriendAccept(userId)
        friends.updateStatus(userId, FriendStatus.ACCEPTED)
    }

    private suspend fun sendFriendAccept(userId: String) =
        sender.sendOrThrow(userId, clientMessage { setFriendAccept(friendAccept {}) })

    /** Forget [userId] and their devices locally. Nothing is sent to them. */
    suspend fun removeFriend(userId: String) {
        friends.remove(userId)
        devices.replace(userId, emptyList())
    }

    suspend fun getFriends(): List<FriendData> = friends.getAll()

    private suspend fun refreshOwnDirectory(self: Credentials) {
        storeOwnDirectory(self, fetchOwnDirectory())
    }

    private suspend fun fetchOwnDirectory(): List<String> =
        try {
            api.fetchOwnDeviceIds()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("DIRECTORY own refresh failed: ${e.message}")
            throw e
        }

    private suspend fun storeOwnDirectory(self: Credentials, ids: List<String>) {
        if (auth.credentials?.deviceId == self.deviceId && auth.credentials?.userId == self.userId) {
            devices.replace(self.userId, ids)
        }
    }

    private fun clientMessage(build: ClientMessage.Builder.() -> Unit): ClientMessage =
        ClientMessage.newBuilder().setTimestamp(System.currentTimeMillis()).apply(build).build()

    private fun log(message: String) {
        debugLog.addFirst("[${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())}] $message")
        while (debugLog.size > DEBUG_LOG_SIZE) debugLog.pollLast()
        logger.log(message)
    }

    private companion object {
        const val MESSAGE_RECEIVED_CAPACITY = 100
        const val DEBUG_LOG_SIZE = 200
        const val PREKEY_MIN_COUNT = 20L
        const val PREKEY_REPLENISH_COUNT = 50
        const val PUSH_IDLE_MS = 500L
        const val PUSH_RECENT_ACTIVITY_MS = 10_000L
    }
}

/**
 * A peer's `sentAt` capped at now + 60 s so it cannot win every later conflict. `AppEntry.timestamp`
 * is a uint64 that arrives negative above 2^63; that is far future too.
 */
internal fun clampFutureTimestamp(sentAt: Long, nowMs: Long = System.currentTimeMillis()): Long {
    val cap = nowMs + 60_000L
    return if (sentAt < 0) cap else minOf(sentAt, cap)
}
