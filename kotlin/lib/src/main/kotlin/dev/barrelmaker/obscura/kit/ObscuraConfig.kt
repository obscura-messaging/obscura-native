package dev.barrelmaker.obscura.kit

/** [databasePath] null means an in-memory database. */
data class ObscuraConfig(
    val apiUrl: String,
    val deviceName: String,
    val databasePath: String? = null,
) {
    init {
        // Parse the host exactly: a prefix check would let "http://localhost@evil.com" send cleartext
        // to a remote host.
        val host = runCatching { java.net.URI(apiUrl).host }.getOrNull()
        val isLoopback = apiUrl.startsWith("http://") && (host == "localhost" || host == "127.0.0.1" || host == "[::1]")
        require(apiUrl.startsWith("https://") || isLoopback) { "API URL must use HTTPS: $apiUrl" }
    }
}
