package dev.barrelmaker.obscura.kit.messaging

import dev.barrelmaker.obscura.kit.SubmissionResult
import dev.barrelmaker.obscura.kit.SubmissionStatus
import dev.barrelmaker.obscura.kit.ObscuraError
import dev.barrelmaker.obscura.kit.crypto.UuidCodec
import xyz.obscura.server.contracts.ObscuraProtocol.SendMessageRequest
import xyz.obscura.server.contracts.ObscuraProtocol.SendMessageResponse
import xyz.obscura.server.contracts.ObscuraProtocol.SendMessageResponse.ErrorCode

/** A submission's result and the server's error code when it failed. */
internal data class Submitted(val result: SubmissionResult, val errorCode: ErrorCode? = null)

/**
 * One request to one user's devices. [missingDeviceIds] is non-null when the server refused the
 * request for not covering the user's current devices; none of its submissions were then queued.
 */
internal data class SubmitOutcome(val submitted: List<Submitted>, val missingDeviceIds: List<String>? = null)

internal fun decodeSubmissionResults(
    userId: String,
    submissions: List<SendMessageRequest.Submission>,
    response: SendMessageResponse,
): SubmitOutcome {
    val ids = submissions.map { it.submissionId }.toSet()
    val failures = response.failedSubmissionsList
    if (!failures.all { it.submissionId in ids } ||
        failures.map { it.submissionId }.toSet().size != failures.size
    ) throw ObscuraError.ProtocolViolation("Response contains unknown or duplicate submission IDs")
    val mismatches = response.deviceMismatchesList
    val mismatched = failures.any { it.errorCode == ErrorCode.ERROR_CODE_DEVICE_MISMATCH }
    if (mismatches.size > 1 || mismatches.any { UuidCodec.uuidString(it.userId) != userId } || mismatches.isNotEmpty() != mismatched
    ) throw ObscuraError.ProtocolViolation("Response contains an inconsistent device mismatch")
    val missing = mismatches.singleOrNull()?.missingDeviceIdsList?.map {
        UuidCodec.uuidString(it) ?: throw ObscuraError.ProtocolViolation("Response contains an invalid device ID")
    }
    val byId = failures.associateBy { it.submissionId }
    return SubmitOutcome(submissions.map {
        val failure = byId[it.submissionId]
        Submitted(
            SubmissionResult(
                userId, deviceIdOf(it),
                if (failure == null) SubmissionStatus.ACCEPTED else SubmissionStatus.REJECTED,
                failure?.let { f -> f.errorMessage.ifEmpty { f.errorCode.name } },
            ),
            failure?.errorCode,
        )
    }, missing)
}

internal fun unknownSubmission(userId: String, submission: SendMessageRequest.Submission, error: String) =
    Submitted(SubmissionResult(userId, deviceIdOf(submission), SubmissionStatus.UNKNOWN, error))

private fun deviceIdOf(submission: SendMessageRequest.Submission) = checkNotNull(UuidCodec.uuidString(submission.deviceId))
