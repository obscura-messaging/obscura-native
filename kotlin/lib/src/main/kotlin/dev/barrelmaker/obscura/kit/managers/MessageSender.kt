package dev.barrelmaker.obscura.kit.managers

import dev.barrelmaker.obscura.kit.ObscuraError
import dev.barrelmaker.obscura.kit.SubmissionResult
import dev.barrelmaker.obscura.kit.SubmissionStatus
import dev.barrelmaker.obscura.kit.messaging.Messenger
import dev.barrelmaker.obscura.kit.messaging.PeerBundle
import dev.barrelmaker.obscura.kit.messaging.Submitted
import dev.barrelmaker.obscura.kit.messaging.UserSubmission
import dev.barrelmaker.obscura.kit.messaging.parsePeerBundle
import dev.barrelmaker.obscura.kit.messaging.parsePeerBundles
import dev.barrelmaker.obscura.kit.messaging.requireConsistent
import dev.barrelmaker.obscura.kit.network.APIClient
import dev.barrelmaker.obscura.kit.stores.DeviceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import obscura.client.v1.Client.ClientMessage
import xyz.obscura.server.contracts.ObscuraProtocol.SendMessageResponse.ErrorCode
import java.util.concurrent.ConcurrentHashMap

/**
 * The one path every outgoing message takes to users' devices. Each request names every target user
 * in `recipient_user_ids`, and the server queues a user's submissions only when they cover all of
 * that user's current devices, so its response decides the targets: they start as the user's
 * devices in [DeviceStore] (this device excluded), a mismatch adds the devices the server names for
 * that user (while other users in the batch are already queued and settled), and an invalid device
 * is removed. A user with no target is still named in `recipient_user_ids`: it is how this device
 * learns of new devices without an extra round-trip.
 *
 * Fetching a bundle consumes a one-time prekey, so bundles are fetched only for a target without a
 * session, one device at a time; a user absent from the directory is fetched whole. A bundle is
 * never processed over an existing session.
 */
internal class MessageSender(
    private val api: APIClient,
    private val messenger: Messenger,
    private val devices: DeviceStore,
    private val selfDeviceId: () -> String?,
) {
    // Sends to the same user are serialized in sorted userId order, so concurrent first contacts
    // share one fetch and overlapping multi-user batches cannot deadlock.
    private val recipientLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun send(
        userId: String,
        message: ClientMessage,
        acceptedDeviceIds: Set<String> = emptySet(),
    ): List<SubmissionResult> = send(listOf(userId), message, acceptedDeviceIds)

    /**
     * Sends [message] to every device of [userIds] in a single batched request per round. Users
     * that succeed in round 1 are settled immediately; only users whose device set mismatched retry
     * in the next round.
     */
    suspend fun send(
        userIds: List<String>,
        message: ClientMessage,
        acceptedDeviceIds: Set<String> = emptySet(),
    ): List<SubmissionResult> {
        val users = userIds.distinct()
        if (users.isEmpty()) return emptyList()
        return withUserLocks(users) {
            val settled = mutableMapOf<String, List<SubmissionResult>>()
            val mismatched = mutableMapOf<String, Pair<List<Submitted>, List<String>>>()
            var pending = users

            repeat(MAX_ROUNDS) {
                if (pending.isEmpty()) return@repeat
                val nextPending = mutableListOf<String>()
                val probeUsers = linkedMapOf<String, List<String>>()
                val targetUsers = linkedMapOf<String, List<String>>()
                val batchSubmissions = mutableListOf<UserSubmission>()

                for (userId in pending) {
                    val listed = targetsOf(userId)
                    if (listed.isNotEmpty() && listed.all { it in acceptedDeviceIds }) {
                        probeUsers[userId] = listed
                        continue
                    }
                    try {
                        val targets = sessionTargets(userId)
                        if (targets == null) {
                            nextPending.add(userId)
                            continue
                        }
                        if (devices.devicesOf(userId).isEmpty()) {
                            settled[userId] = listOf(rejected(userId, "No devices found"))
                            continue
                        }
                        val subs = targets.map { UserSubmission(userId, messenger.prepareSubmission(it, message)) }
                        targetUsers[userId] = targets
                        batchSubmissions.addAll(subs)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        settled[userId] = listOf(rejected(userId, e))
                    }
                }

                val activeUsers = (probeUsers.keys + targetUsers.keys).toList()
                if (activeUsers.isNotEmpty()) {
                    val outcome = messenger.submit(activeUsers, batchSubmissions)
                    outcome.submitted.filter { it.errorCode == ErrorCode.ERROR_CODE_INVALID_DEVICE }
                        .groupBy({ it.result.userId }, { it.result.deviceId!! })
                        .forEach { (u, invalid) -> devices.remove(u, invalid) }

                    for ((userId, listed) in probeUsers) {
                        val missing = outcome.mismatches[userId].orEmpty()
                        if (missing.all { it in acceptedDeviceIds }) {
                            settled[userId] = listed.map { SubmissionResult(userId, it, SubmissionStatus.ACCEPTED) }
                        } else {
                            devices.add(userId, missing)
                            mismatched[userId] = emptyList<Submitted>() to missing
                            nextPending.add(userId)
                        }
                    }

                    for ((userId, targets) in targetUsers) {
                        val userSubmitted = outcome.submitted.filter { it.result.userId == userId }
                        val missing = outcome.mismatches[userId]
                        if (missing != null) {
                            devices.add(userId, missing)
                            mismatched[userId] = userSubmitted to missing
                            nextPending.add(userId)
                        } else {
                            settled[userId] = when {
                                targets.none { it in acceptedDeviceIds } && devices.devicesOf(userId).isEmpty() ->
                                    listOf(rejected(userId, "No devices found"))
                                else -> userSubmitted.map {
                                    if (it.result.deviceId in acceptedDeviceIds) it.result.copy(status = SubmissionStatus.ACCEPTED, error = null) else it.result
                                }
                            }
                        }
                    }
                }
                pending = nextPending
            }

            for (userId in pending) {
                val last = mismatched[userId]
                if (last == null) {
                    settled[userId] = listOf(rejected(userId, "Sessions could not be established"))
                } else {
                    val (submitted, missing) = last
                    val error = "Device mismatch after $MAX_ROUNDS rounds; missing $missing"
                    settled[userId] = submitted.map {
                        if (it.errorCode == ErrorCode.ERROR_CODE_DEVICE_MISMATCH) it.result.copy(error = error) else it.result
                    }.ifEmpty { listOf(rejected(userId, error)) }
                }
            }

            users.flatMap { settled[it].orEmpty() }
        }
    }

    private suspend fun <T> withUserLocks(users: List<String>, block: suspend () -> T): T {
        val locks = users.sorted().map { recipientLocks.computeIfAbsent(it) { Mutex() } }
        suspend fun acquire(index: Int): T =
            if (index == locks.size) block() else locks[index].withLock { acquire(index + 1) }
        return acquire(0)
    }

    /** [send], failing when no device accepted. For control messages that must reach the user. */
    suspend fun sendOrThrow(userId: String, message: ClientMessage) {
        val results = send(userId, message)
        if (results.none { it.status == SubmissionStatus.ACCEPTED }) {
            throw ObscuraError.SendFailed(results.joinToString { it.error ?: it.status.name })
        }
    }

    private suspend fun targetsOf(userId: String): List<String> = selfDeviceId().let { self -> devices.devicesOf(userId).filter { it != self } }

    /**
     * The targets once each has a session, or null when building one dropped another's: a changed
     * account identity drops every session with the user, and the next round fetches those.
     */
    private suspend fun sessionTargets(userId: String): List<String>? {
        if (devices.devicesOf(userId).isEmpty()) establish(userId, fetchAll(userId))
        establish(userId, targetsOf(userId).filter { !messenger.hasSession(it) }.mapNotNull { fetchOne(userId, it) })
        val targets = targetsOf(userId)
        return targets.takeIf { all -> all.all { messenger.hasSession(it) } }
    }

    private suspend fun fetchAll(userId: String): List<PeerBundle> = try {
        parsePeerBundles(api.fetchPreKeyBundles(userId)).filter { it.deviceId != selfDeviceId() }
    } catch (e: ObscuraError.Http) {
        if (e.status == 404) emptyList() else throw e
    }

    /** Null, and the device forgotten, when the server no longer has it. */
    private suspend fun fetchOne(userId: String, deviceId: String): PeerBundle? = try {
        parsePeerBundle(api.fetchPreKeyBundle(userId, deviceId)).also {
            if (it.deviceId != deviceId) throw ObscuraError.ProtocolViolation("Bundle for $deviceId names device ${it.deviceId}")
        }
    } catch (e: ObscuraError.Http) {
        if (e.status != 404) throw e
        devices.remove(userId, listOf(deviceId))
        null
    }

    /** Bundles that disagree store nothing. The directory comes first: identity checks resolve through it. */
    private suspend fun establish(userId: String, bundles: List<PeerBundle>) {
        if (bundles.isEmpty()) return
        requireConsistent(bundles)
        devices.add(userId, bundles.map { it.deviceId })
        bundles.forEach { messenger.establishSession(it) }
    }

    private fun rejected(userId: String, error: Any) = SubmissionResult(
        userId, null, SubmissionStatus.REJECTED,
        if (error is Throwable) error.message ?: error.javaClass.simpleName else error.toString(),
    )

    private companion object {
        const val MAX_ROUNDS = 3
    }
}
