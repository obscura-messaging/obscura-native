package dev.barrelmaker.obscura.kit

enum class TypingState { STARTED, STOPPED }

enum class AuthState { LOGGED_OUT, AUTHENTICATED }

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

enum class LoginScenario {
    /** Authenticated as the stored local device. */
    EXISTING_DEVICE,

    /** No local device: call `loginAndProvision`. */
    NEW_DEVICE,

    /** The server does not know the stored local device: `wipeDevice`, then `loginAndProvision`. */
    DEVICE_MISMATCH,
    INVALID_CREDENTIALS,
}

enum class SubmissionStatus { ACCEPTED, REJECTED, UNKNOWN }

data class SubmissionResult(
    val userId: String,
    val deviceId: String?,
    val status: SubmissionStatus,
    val error: String? = null,
)

data class SendReport(val recipients: List<SubmissionResult>, val selfSync: List<SubmissionResult>)

/** An uploaded attachment: its server id and the key material that decrypts it. */
class AttachmentUpload(val id: String, val contentKey: ByteArray, val nonce: ByteArray)
