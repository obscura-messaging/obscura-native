package dev.barrelmaker.obscura.kit.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The gateway against a local server that can end the WebSocket from its side. */
class GatewayConnectionTest {
    private val server = MockWebServer().apply { start() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gateway = GatewayConnection(APIClient(server.url("/").toString().trimEnd('/')).apply { token = "t" }, scope)

    @AfterEach fun close() {
        scope.cancel()
        server.close()
    }

    private fun enqueueGateway(onOpen: (WebSocket) -> Unit = {}) {
        server.enqueue(MockResponse.Builder().body("""{"ticket":"t"}""").build())
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) = onOpen(webSocket)
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        }).build())
    }

    @Test
    fun `a close the server initiates is noticed and followed by a reconnect`() = runBlocking {
        enqueueGateway { it.close(1000, "taken over") }
        enqueueGateway()

        gateway.connect()
        withTimeout(5_000) { gateway.state.first { it == GatewayState.RECONNECTING } }
        withTimeout(5_000) { gateway.state.first { it == GatewayState.CONNECTED } }

        assertEquals(4, server.requestCount)
    }

    @Test
    fun `a client disconnect does not reconnect`() = runBlocking {
        enqueueGateway()
        enqueueGateway()

        gateway.connect()
        gateway.disconnect()
        delay(1_500)

        assertEquals(GatewayState.DISCONNECTED, gateway.state.value)
        assertEquals(2, server.requestCount)
    }
}
