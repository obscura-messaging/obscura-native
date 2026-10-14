package dev.barrelmaker.obscura.kit.managers

import dev.barrelmaker.obscura.kit.ObscuraError
import dev.barrelmaker.obscura.kit.SubmissionResult
import dev.barrelmaker.obscura.kit.SubmissionStatus
import dev.barrelmaker.obscura.kit.messaging.Messenger
import dev.barrelmaker.obscura.kit.messaging.Submitted
import dev.barrelmaker.obscura.kit.messaging.parsePeerBundles
import dev.barrelmaker.obscura.kit.network.APIClient
import dev.barrelmaker.obscura.kit.stores.DeviceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import obscura.client.v1.Client.ClientMessage
import java.util.concurrent.ConcurrentHashMap

/**
 * The one path every outgoing message takes to a user's devices. Devices come from [DeviceStore]
 * and existing sessions are reused. Fetching bundles consumes a one-time prekey on each of the
 * user's devices, so it happens only when the user has no listed devices, a target has no session,
 * or the server rejects a listed device as nonexistent (then once, retrying the server's list).
 * A device whose bundle cannot be processed is left out so it cannot cause a fetch on every send.
 * This device is never a target.
 */
internal class MessageSender(
    private val api: APIClient,
    private val messenger: Messenger,
    private val devices: DeviceStore,
    private val selfDeviceId: () -> String?,
) {
    // Sends to one user are serialized, so concurrent first contacts share one fetch.
    private val recipientLocks = ConcurrentHashMap<String, Mutex>()

    /** Devices in [acceptedDeviceIds] are reported accepted without being sent again. */
    suspend fun send(
        userId: String,
        message: ClientMessage,
        acceptedDeviceIds: Set<String> = emptySet(),
    ): List<SubmissionResult> = recipientLocks.computeIfAbsent(userId) { Mutex() }.withLock {
        val self = selfDeviceId()
        val targetsOf = { ids: List<String> -> ids.filter { it != self } }
        val listed = devices.devicesOf(userId)
        val refreshed = listed.isEmpty() ||
            targetsOf(listed).any { it !in acceptedDeviceIds && !messenger.hasSession(it) }
        val resolved = if (refreshed) {
            refresh(userId).getOrElse { return@withLock listOf(rejected(userId, null, it)) }
        } else {
            Resolved(listed, emptyList())
        }
        if (resolved.deviceIds.isEmpty() && resolved.failures.isEmpty()) {
            return@withLock listOf(rejected(userId, null, "No devices found"))
        }

        val targets = targetsOf(resolved.deviceIds)
        val submitted = submit(userId, targets, message, acceptedDeviceIds)
        val firstPass = resolved.failures + submitted.map { it.result }
        val missing = submitted.filter { it.deviceMissing }.mapNotNull { it.result.deviceId }.toSet()
        if (refreshed || missing.isEmpty()) return@withLock firstPass

        val fresh = refresh(userId).getOrElse { return@withLock firstPass }
        val gone = missing - fresh.deviceIds.toSet()
        val added = targetsOf(fresh.deviceIds) - targets.toSet()
        (firstPass.filterNot { it.deviceId in gone } + fresh.failures +
            submit(userId, added, message, acceptedDeviceIds).map { it.result })
            .ifEmpty { listOf(rejected(userId, null, "No devices found")) }
    }

    /** [send], failing when no device accepted. For control messages that must reach the user. */
    suspend fun sendOrThrow(userId: String, message: ClientMessage) {
        val results = send(userId, message)
        if (results.none { it.status == SubmissionStatus.ACCEPTED }) {
            throw ObscuraError.SendFailed(results.joinToString { it.error ?: it.status.name })
        }
    }

    private data class Resolved(val deviceIds: List<String>, val failures: List<SubmissionResult>)

    private suspend fun refresh(userId: String): Result<Resolved> = catching {
        val bundles = try {
            parsePeerBundles(api.fetchPreKeyBundles(userId))
        } catch (e: ObscuraError.Http) {
            if (e.status == 404) emptyList() else throw e
        }
        val failures = bundles.filter { it.deviceId != selfDeviceId() }.mapNotNull { peer ->
            catching { messenger.establishSession(peer) }.exceptionOrNull()?.let { rejected(userId, peer.deviceId, it) }
        }
        val failed = failures.mapNotNull { it.deviceId }.toSet()
        val deviceIds = bundles.map { it.deviceId }.filterNot { it in failed }
        devices.replace(userId, deviceIds)
        Resolved(deviceIds, failures)
    }

    private suspend fun submit(
        userId: String,
        deviceIds: List<String>,
        message: ClientMessage,
        acceptedDeviceIds: Set<String>,
    ): List<Submitted> {
        val (alreadyAccepted, pending) = deviceIds.partition { it in acceptedDeviceIds }
        val prepared = pending.map { it to catching { messenger.prepareSubmission(it, message) } }
        return alreadyAccepted.map { Submitted(SubmissionResult(userId, it, SubmissionStatus.ACCEPTED)) } +
            prepared.mapNotNull { (id, r) -> r.exceptionOrNull()?.let { Submitted(rejected(userId, id, it)) } } +
            messenger.submit(userId, prepared.mapNotNull { it.second.getOrNull() })
    }

    private fun rejected(userId: String, deviceId: String?, error: Any) = SubmissionResult(
        userId, deviceId, SubmissionStatus.REJECTED,
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
}
