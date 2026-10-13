/// Whether the session has a usable access token after a freshness check.
public enum TokenStatus: Sendable, Equatable {
    /// A valid access token is available.
    case fresh
    /// The server refused the refresh token, or there is none. The session is over and signed out.
    case rejected
    /// The refresh could not complete (network or server error). The session is kept and retried.
    case unavailable
}
