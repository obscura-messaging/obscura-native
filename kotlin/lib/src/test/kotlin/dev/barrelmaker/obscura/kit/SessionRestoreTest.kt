package dev.barrelmaker.obscura.kit

import com.sun.net.httpserver.HttpServer
import dev.barrelmaker.obscura.kit.persistence.SessionStorage
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.Base64

/** Restoring a stored session whose access token has expired, against each refresh outcome. */
class SessionRestoreTest {
    private class MemoryStorage(var data: Map<String, Any?>?) : SessionStorage {
        override fun save(data: Map<String, Any?>) { this.data = data }
        override fun load() = data
        override fun clear() { data = null }
    }

    private var refreshStatus = 503
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1/sessions/refresh") { exchange ->
            val body = if (refreshStatus == 200) """{"token":"${jwt(4_102_444_800)}","refreshToken":"rotated"}""" else "{}"
            exchange.sendResponseHeaders(refreshStatus, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        start()
    }
    private val url = "http://127.0.0.1:${server.address.port}"

    @AfterEach fun stop() = server.stop(0)

    private fun jwt(exp: Long): String {
        val encode = { s: String -> Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray()) }
        return "${encode("""{"alg":"none"}""")}.${encode("""{"exp":$exp}""")}.sig"
    }

    private fun storedSession() = MemoryStorage(mapOf(
        "token" to jwt(exp = 1), "refreshToken" to "rt", "userId" to "u1", "deviceId" to "d1", "username" to "alice",
    ))

    private fun client(apiUrl: String, storage: SessionStorage) =
        ObscuraClient(ObscuraConfig(apiUrl = apiUrl), sessionStorage = storage)

    @Test
    fun `a server error keeps the session for an offline launch`() = runBlocking {
        val storage = storedSession()
        val c = client(url, storage)

        assertFalse(c.restorePersistedSession())

        assertEquals(AuthState.AUTHENTICATED, c.authState.value)
        assertEquals("rt", storage.data?.get("refreshToken"))
    }

    @Test
    fun `an unreachable server keeps the session`() = runBlocking {
        val storage = storedSession()
        val c = client("http://127.0.0.1:1", storage)

        assertFalse(c.restorePersistedSession())

        assertEquals(AuthState.AUTHENTICATED, c.authState.value)
        assertEquals("rt", storage.data?.get("refreshToken"))
    }

    @Test
    fun `a rejected refresh token signs out and forgets the session`() = runBlocking {
        refreshStatus = 401
        val storage = storedSession()
        val c = client(url, storage)

        assertFalse(c.restorePersistedSession())

        assertEquals(AuthState.LOGGED_OUT, c.authState.value)
        assertNull(storage.data)
    }

    @Test
    fun `a successful refresh is fresh and persists the rotated refresh token`() = runBlocking {
        refreshStatus = 200
        val storage = storedSession()
        val c = client(url, storage)
        c.restoreSession(token = jwt(exp = 1), refreshToken = "rt", userId = "u1", deviceId = "d1", username = "alice")

        assertEquals(TokenStatus.FRESH, c.ensureFreshToken())
        assertEquals("rotated", storage.data?.get("refreshToken"))
    }

    @Test
    fun `a session with no refresh token is rejected`() = runBlocking {
        val storage = storedSession()
        val c = client(url, storage)
        c.restoreSession(token = jwt(exp = 1), refreshToken = null, userId = "u1", deviceId = "d1", username = "alice")

        assertEquals(TokenStatus.REJECTED, c.ensureFreshToken())
        assertEquals(AuthState.LOGGED_OUT, c.authState.value)
    }
}
