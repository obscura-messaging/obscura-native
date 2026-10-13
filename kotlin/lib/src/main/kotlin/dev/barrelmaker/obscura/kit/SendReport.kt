package dev.barrelmaker.obscura.kit

enum class SubmissionStatus { ACCEPTED, REJECTED, UNKNOWN }

data class SubmissionResult(
    val userId: String,
    val deviceId: String?,
    val status: SubmissionStatus,
    val error: String? = null,
)

data class SendReport(
    val recipients: List<SubmissionResult>,
    val selfSync: List<SubmissionResult>,
)
