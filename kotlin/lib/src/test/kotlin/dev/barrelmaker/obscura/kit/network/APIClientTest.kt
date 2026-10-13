package dev.barrelmaker.obscura.kit.network

import dev.barrelmaker.obscura.kit.ObscuraError
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class APIClientTest {
    private val server = MockWebServer().apply { start() }
    private val api = APIClient(server.url("/").toString().trimEnd('/')) { "token" }

    @AfterEach fun close() = server.close()

    private fun status(code: Int, retryAfter: String? = null, body: String = "") = MockResponse.Builder().code(code).body(body)
        .apply { if (retryAfter != null) setHeader("Retry-After", retryAfter) }.build()

    @Test
    fun `429 and 503 are retried twice after Retry-After`() = runBlocking {
        server.enqueue(status(429, retryAfter = "0"))
        server.enqueue(status(503))
        server.enqueue(status(200, body = """{"ticket":"t"}"""))

        val started = System.currentTimeMillis()
        assertEquals("t", api.fetchGatewayTicket())

        assertEquals(3, server.requestCount)
        assertTrue(System.currentTimeMillis() - started >= 2_000, "each retry waits at least 1 s")
        assertEquals("Bearer token", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `a third rate limit is thrown with status and body`() = runBlocking {
        repeat(3) { server.enqueue(status(429, retryAfter = "1", body = "slow down")) }

        val error = runCatching { api.fetchGatewayTicket() }.exceptionOrNull() as ObscuraError.Http

        assertEquals(429, error.status)
        assertEquals("slow down", error.body)
        assertEquals("HTTP_ERROR", error.code)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `other errors are not retried`() = runBlocking {
        server.enqueue(status(500, body = "boom"))
        val error = runCatching { api.fetchGatewayTicket() }.exceptionOrNull() as ObscuraError.Http
        assertEquals(500, error.status)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `an authenticated call without a token is NOT_AUTHENTICATED and sends nothing`() = runBlocking {
        val signedOut = APIClient(server.url("/").toString().trimEnd('/')) { null }
        assertTrue(runCatching { signedOut.fetchAttachment("a") }.exceptionOrNull() is ObscuraError.NotAuthenticated)
        assertEquals(0, server.requestCount)
    }
}
