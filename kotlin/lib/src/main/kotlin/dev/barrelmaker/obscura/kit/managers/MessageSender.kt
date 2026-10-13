package dev.barrelmaker.obscura.kit.managers

import dev.barrelmaker.obscura.kit.messaging.Messenger
import dev.barrelmaker.obscura.kit.SubmissionResult
import dev.barrelmaker.obscura.kit.SubmissionStatus
import dev.barrelmaker.obscura.kit.network.APIClient
import obscura.client.v1.Client.ClientMessage

/**
 * Shared utility for sending a ClientMessage to all of a user's devices.
 * Used by every manager that needs to send encrypted messages.
 */
internal class MessageSender(
    private val messenger: Messenger,
    private val authManager: AuthManager,
    private val api: APIClient,
) {
    suspend fun sendToAllDevices(targetUserId: String, msg: ClientMessage) {
        val results = attemptToAllDevices(targetUserId, msg)
        if (results.none { it.status == SubmissionStatus.ACCEPTED }) {
            throw dev.barrelmaker.obscura.kit.ObscuraError.SendFailed(
                results.joinToString { it.error ?: it.status.name }
            )
        }
    }

    suspend fun attemptToAllDevices(
        targetUserId: String,
        msg: ClientMessage,
        acceptedDeviceIds: Set<String> = emptySet(),
    ): List<SubmissionResult> {
        val deviceIds = try {
            authManager.ensureFreshToken()
            val bundles = api.fetchPreKeyBundles(targetUserId)
            (0 until bundles.length()).map { bundles.getJSONObject(it).getString("deviceId") }.distinct()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return listOf(SubmissionResult(targetUserId, null, SubmissionStatus.REJECTED, e.message ?: e.javaClass.simpleName))
        }
        if (deviceIds.isEmpty()) {
            return listOf(SubmissionResult(targetUserId, null, SubmissionStatus.REJECTED, "No devices found"))
        }
        return attemptDevices(targetUserId, deviceIds, msg, acceptedDeviceIds)
    }

    suspend fun attemptDevices(
        userId: String,
        deviceIds: List<String>,
        msg: ClientMessage,
        acceptedDeviceIds: Set<String>,
    ): List<SubmissionResult> {
        val results = mutableListOf<SubmissionResult>()
        val submissions = mutableListOf<xyz.obscura.server.contracts.ObscuraProtocol.SendMessageRequest.Submission>()
        for (deviceId in deviceIds.distinct()) {
            if (deviceId in acceptedDeviceIds) {
                results.add(SubmissionResult(userId, deviceId, SubmissionStatus.ACCEPTED))
                continue
            }
            try {
                submissions.add(messenger.prepareSubmission(deviceId, msg, userId))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                results.add(SubmissionResult(userId, deviceId, SubmissionStatus.REJECTED, e.message ?: e.javaClass.simpleName))
            }
        }
        return results + messenger.submit(userId, submissions)
    }
}
