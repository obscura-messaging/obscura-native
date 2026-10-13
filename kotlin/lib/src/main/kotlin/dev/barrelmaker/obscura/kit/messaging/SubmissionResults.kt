package dev.barrelmaker.obscura.kit.messaging

import dev.barrelmaker.obscura.kit.SubmissionResult
import dev.barrelmaker.obscura.kit.SubmissionStatus
import dev.barrelmaker.obscura.kit.crypto.UuidCodec
import xyz.obscura.server.contracts.ObscuraProtocol.SendMessageRequest
import xyz.obscura.server.contracts.ObscuraProtocol.SendMessageResponse

internal fun decodeSubmissionResults(
    userId: String,
    submissions: List<SendMessageRequest.Submission>,
    response: SendMessageResponse,
): List<SubmissionResult> {
    val ids = submissions.map { it.submissionId }.toSet()
    val failures = response.failedSubmissionsList
    require(failures.all { it.submissionId in ids } &&
        failures.map { it.submissionId }.toSet().size == failures.size) {
        "Response contains unknown or duplicate submission IDs"
    }
    val byId = failures.associateBy { it.submissionId }
    return submissions.map {
        val failure = byId[it.submissionId]
        SubmissionResult(
            userId, UuidCodec.bytesToUuid(it.deviceId.toByteArray()).toString(),
            if (failure == null) SubmissionStatus.ACCEPTED else SubmissionStatus.REJECTED,
            failure?.let { f -> f.errorMessage.ifEmpty { f.errorCode.name } },
        )
    }
}
