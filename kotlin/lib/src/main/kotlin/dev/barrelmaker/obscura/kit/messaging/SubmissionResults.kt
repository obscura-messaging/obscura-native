package dev.barrelmaker.obscura.kit.messaging

import dev.barrelmaker.obscura.kit.SubmissionResult
import dev.barrelmaker.obscura.kit.SubmissionStatus
import dev.barrelmaker.obscura.kit.crypto.UuidCodec
import xyz.obscura.server.contracts.ObscuraProtocol.SendMessageRequest
import xyz.obscura.server.contracts.ObscuraProtocol.SendMessageResponse

/** A submission's result, plus whether the server said its device no longer exists. */
internal data class Submitted(val result: SubmissionResult, val deviceMissing: Boolean = false)

internal fun decodeSubmissionResults(
    userId: String,
    submissions: List<SendMessageRequest.Submission>,
    response: SendMessageResponse,
): List<Submitted> {
    val ids = submissions.map { it.submissionId }.toSet()
    val failures = response.failedSubmissionsList
    require(failures.all { it.submissionId in ids } &&
        failures.map { it.submissionId }.toSet().size == failures.size) {
        "Response contains unknown or duplicate submission IDs"
    }
    val byId = failures.associateBy { it.submissionId }
    return submissions.map {
        val failure = byId[it.submissionId]
        Submitted(
            SubmissionResult(
                userId, deviceIdOf(it),
                if (failure == null) SubmissionStatus.ACCEPTED else SubmissionStatus.REJECTED,
                failure?.let { f -> f.errorMessage.ifEmpty { f.errorCode.name } },
            ),
            deviceMissing = failure?.errorCode == SendMessageResponse.ErrorCode.ERROR_CODE_INVALID_DEVICE,
        )
    }
}

internal fun unknownSubmission(userId: String, submission: SendMessageRequest.Submission, error: String) =
    Submitted(SubmissionResult(userId, deviceIdOf(submission), SubmissionStatus.UNKNOWN, error))

private fun deviceIdOf(submission: SendMessageRequest.Submission) =
    UuidCodec.bytesToUuid(submission.deviceId.toByteArray()).toString()
