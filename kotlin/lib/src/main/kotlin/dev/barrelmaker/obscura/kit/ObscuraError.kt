package dev.barrelmaker.obscura.kit

/** Every error the kit throws on purpose. [code] is the stable contract; the message is for humans. */
sealed class ObscuraError(val code: String, message: String) : Exception(message) {
    class NotAuthenticated : ObscuraError("NOT_AUTHENTICATED", "Not authenticated")

    /** A message that must reach a user was accepted by none of their devices. */
    class SendFailed(message: String) : ObscuraError("SEND_FAILED", message)

    /** The server answered with a non-2xx status. */
    class Http(val status: Int, val body: String) : ObscuraError("HTTP_ERROR", "HTTP $status")
}
