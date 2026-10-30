package dev.barrelmaker.obscura.kit.messaging

import dev.barrelmaker.obscura.kit.ObscuraError
import dev.barrelmaker.obscura.kit.SubmissionResult
import dev.barrelmaker.obscura.kit.SubmissionStatus
import dev.barrelmaker.obscura.kit.crypto.UuidCodec
import xyz.obscura.server.contracts.ObscuraProtocol.SendMessageRequest
import xyz.obscura.server.contracts.ObscuraProtocol.SendMessageResponse
import xyz.obscura.server.contracts.ObscuraProtocol.SendMessageResponse.ErrorCode

/** One submission and the recipient user it addresses. */
internal data class UserSubmission(val userId: String, val submission: SendMessageRequest.Submission)

/** A submission's result and the server's error code when it failed. */
internal data class Submitted(val result: SubmissionResult, val errorCode: ErrorCode? = null)

/**
 * One request covering one or more users. [mismatches] maps each rejected user to the current
 * devices the request did not target; none of that user's submissions were then queued.
 */
internal data class SubmitOutcome(
    val submitted: List<Submitted>,
    val mismatches: Map<String, List<String>> = emptyMap(),
) {
    val missingDeviceIds: List<String>? get() = mismatches.values.singleOrNull()
}

internal fun decodeSubmissionResults(
    userIds: List<String>,
    submissions: List<UserSubmission>,
    response: SendMessageResponse,
): SubmitOutcome {
    val ids = submissions.map { it.submission.submissionId }.toSet()
    val failures = response.failedSubmissionsList
    if (!failures.all { it.submissionId in ids } ||
        failures.map { it.submissionId }.toSet().size != failures.size
    ) throw ObscuraError.ProtocolViolation("Response contains unknown or duplicate submission IDs")
    val addressed = userIds.toSet()
    val mismatches = mutableMapOf<String, List<String>>()
    // A mismatch with no failed submission is how the server names devices of a user the request targeted none of.
    for (m in response.deviceMismatchesList) {
        val user = UuidCodec.uuidString(m.userId)
        if (user == null || user !in addressed || user in mismatches) {
            throw ObscuraError.ProtocolViolation("Response contains an inconsistent device mismatch")
        }
        mismatches[user] = m.missingDeviceIdsList.map {
            UuidCodec.uuidString(it) ?: throw ObscuraError.ProtocolViolation("Response contains an invalid device ID")
        }
    }
    val byId = failures.associateBy { it.submissionId }
    val submitted = submissions.map { (userId, sub) ->
        val failure = byId[sub.submissionId]
        if (failure?.errorCode == ErrorCode.ERROR_CODE_DEVICE_MISMATCH && userId !in mismatches) {
            throw ObscuraError.ProtocolViolation("Response contains an inconsistent device mismatch")
        }
        Submitted(
            SubmissionResult(
                userId, deviceIdOf(sub),
                if (failure == null) SubmissionStatus.ACCEPTED else SubmissionStatus.REJECTED,
                failure?.let { f -> f.errorMessage.ifEmpty { f.errorCode.name } },
            ),
            failure?.errorCode,
        )
    }
    return SubmitOutcome(submitted, mismatches)
}

internal fun decodeSubmissionResults(
    userId: String,
    submissions: List<SendMessageRequest.Submission>,
    response: SendMessageResponse,
): SubmitOutcome = decodeSubmissionResults(
    listOf(userId),
    submissions.map { UserSubmission(userId, it) },
    response,
)

internal fun unknownSubmission(userId: String, submission: SendMessageRequest.Submission, error: String) =
    Submitted(SubmissionResult(userId, deviceIdOf(submission), SubmissionStatus.UNKNOWN, error))

private fun deviceIdOf(submission: SendMessageRequest.Submission) = checkNotNull(UuidCodec.uuidString(submission.deviceId))
