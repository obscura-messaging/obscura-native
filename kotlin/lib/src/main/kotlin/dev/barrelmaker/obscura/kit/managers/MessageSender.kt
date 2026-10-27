package dev.barrelmaker.obscura.kit.managers

import dev.barrelmaker.obscura.kit.ObscuraError
import dev.barrelmaker.obscura.kit.SubmissionResult
import dev.barrelmaker.obscura.kit.SubmissionStatus
import dev.barrelmaker.obscura.kit.messaging.Messenger
import dev.barrelmaker.obscura.kit.messaging.PeerBundle
import dev.barrelmaker.obscura.kit.messaging.SubmitOutcome
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
 * The one path every outgoing message takes to a user's devices. Each request names the user, and
 * the server queues it only when it covers all of the user's current devices, so its response, not
 * discovery, decides the targets: they start as the user's devices in [DeviceStore] (this device
 * excluded), a mismatch adds the devices the server names, and an invalid device is removed. A
 * request with no target is still sent: it is how this device learns of the account's new devices.
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
    // Sends to one user are serialized, so concurrent first contacts share one fetch.
    private val recipientLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * When every known target is in [acceptedDeviceIds], a request with no submission asks the
     * server whether the user has another device: if not, they are reported accepted; if so, the
     * whole device set is submitted, as completeness requires. Otherwise every target is submitted
     * and evidence devices are reported accepted. A user left with no devices and no evidence is
     * one "No devices found" rejection.
     */
    suspend fun send(
        userId: String,
        message: ClientMessage,
        acceptedDeviceIds: Set<String> = emptySet(),
    ): List<SubmissionResult> = recipientLocks.computeIfAbsent(userId) { Mutex() }.withLock {
        var mismatched: SubmitOutcome? = null
        repeat(MAX_ROUNDS) {
            val listed = targetsOf(userId)
            if (listed.isNotEmpty() && listed.all { it in acceptedDeviceIds }) {
                val probe = messenger.submit(userId, emptyList())
                val missing = probe.missingDeviceIds.orEmpty()
                if (missing.all { it in acceptedDeviceIds }) {
                    return@withLock listed.map { SubmissionResult(userId, it, SubmissionStatus.ACCEPTED) }
                }
                devices.add(userId, missing)
                mismatched = probe
                return@repeat
            }
            val targets = catching { sessionTargets(userId) }.getOrElse { return@withLock listOf(rejected(userId, it)) }
                ?: return@repeat
            if (devices.devicesOf(userId).isEmpty()) return@withLock listOf(rejected(userId, "No devices found"))
            val submissions = catching { targets.map { messenger.prepareSubmission(it, message) } }
                .getOrElse { return@withLock listOf(rejected(userId, it)) }
            val outcome = messenger.submit(userId, submissions)
            devices.remove(userId, outcome.submitted.filter { it.errorCode == ErrorCode.ERROR_CODE_INVALID_DEVICE }.map { it.result.deviceId!! })
            val missing = outcome.missingDeviceIds ?: return@withLock when {
                targets.none { it in acceptedDeviceIds } && devices.devicesOf(userId).isEmpty() ->
                    listOf(rejected(userId, "No devices found"))
                else -> outcome.submitted.map {
                    if (it.result.deviceId in acceptedDeviceIds) it.result.copy(status = SubmissionStatus.ACCEPTED, error = null) else it.result
                }
            }
            devices.add(userId, missing)
            mismatched = outcome
        }
        val outcome = mismatched ?: return@withLock listOf(rejected(userId, "Sessions could not be established"))
        val error = "Device mismatch after $MAX_ROUNDS rounds; missing ${outcome.missingDeviceIds}"
        outcome.submitted.map { if (it.errorCode == ErrorCode.ERROR_CODE_DEVICE_MISMATCH) it.result.copy(error = error) else it.result }
            .ifEmpty { listOf(rejected(userId, error)) }
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

    /** [runCatching] that never swallows coroutine cancellation. */
    private inline fun <T> catching(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private companion object {
        const val MAX_ROUNDS = 3
    }
}
