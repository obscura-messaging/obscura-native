package dev.barrelmaker.obscura.kit

/** Whether the session has a usable access token after a freshness check. */
enum class TokenStatus {
    /** A valid access token is available. */
    FRESH,

    /** The server refused the refresh token, or there is none. The session is over and signed out. */
    REJECTED,

    /** The refresh could not complete (network or server error). The session is kept and retried. */
    UNAVAILABLE,
}
