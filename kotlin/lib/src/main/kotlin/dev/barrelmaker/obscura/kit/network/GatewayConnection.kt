package dev.barrelmaker.obscura.kit.network

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import okio.ByteString
import xyz.obscura.server.contracts.ObscuraProtocol.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

enum class GatewayState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING
}

/**
 * WebSocket connection to Obscura gateway.
 *
 * Keeps the connection alive with:
 * - OkHttp ping every 30s (keeps NAT/proxy alive)
 * - Auto-reconnect with exponential backoff (1s → 30s) after any drop or server-initiated close
 * - Token refresh before reconnect attempts
 * - [disconnect] suppresses reconnect
 */
class GatewayConnection(
    private val api: APIClient,
    private val scope: CoroutineScope
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(90, TimeUnit.SECONDS)  // if no data or pong for 90s, connection is dead
        .pingInterval(30, TimeUnit.SECONDS) // keepalive — triggers onFailure if pong missing
        .build()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private var shouldReconnect = true
    private val connectionGeneration = AtomicLong()

    // Completed on onOpen, completed exceptionally on onFailure/onClosed. Lets
    // connect() suspend until the socket is actually open (or has failed) instead
    // of returning the instant OkHttp's async newWebSocket() is kicked off.
    private var openSignal: CompletableDeferred<Unit>? = null

    // Current socket state, exposed two complementary ways:
    //   • [state]          — observable holder (StateFlow) for pull / flow collection
    //   • [onStateChanged] — synchronous push, invoked inline from setState() so the
    //                        one in-process mirror (ObscuraClient.connectionState) is
    //                        updated with zero latency — see setState() below.
    // Both are driven by the single mutation point setState(); callers never touch
    // _state directly.
    private val _state = MutableStateFlow(GatewayState.DISCONNECTED)
    val state: StateFlow<GatewayState> = _state

    /** Fired synchronously on every socket state transition. Set by ObscuraClient. */
    var onStateChanged: ((GatewayState) -> Unit)? = null

    /** The only writer of [_state]; keeps the flow and the callback in lockstep. */
    private fun setState(s: GatewayState) {
        _state.value = s
        onStateChanged?.invoke(s)
    }

    val envelopes = Channel<Envelope>(capacity = 1000)
    val preKeyStatus = Channel<PreKeyStatus>(capacity = 10)

    /** Called before reconnect to ensure token is fresh. Set by ObscuraClient. */
    var ensureFreshToken: (suspend () -> Boolean) = { true }

    suspend fun connect() {
        if (_state.value == GatewayState.CONNECTED || _state.value == GatewayState.CONNECTING) return
        setState(GatewayState.CONNECTING)
        shouldReconnect = true

        val signal = CompletableDeferred<Unit>()
        openSignal = signal
        val generation = connectionGeneration.incrementAndGet()
        try {
            val ticket = api.fetchGatewayTicket()
            val url = api.getGatewayUrl(ticket)
            openWebSocket(url, generation)
            // Suspend until the socket truly opens (onOpen) or fails (onFailure/
            // onClosed). This makes "connect() returned" mean "connected", and
            // lets the listener callbacks be the single owner of the state flow.
            signal.await()
        } catch (e: Exception) {
            if (_state.value != GatewayState.DISCONNECTED) setState(GatewayState.DISCONNECTED)
            throw e
        } finally {
            if (openSignal === signal) openSignal = null
        }
    }

    fun disconnect() {
        shouldReconnect = false
        connectionGeneration.incrementAndGet()
        reconnectJob?.cancel()
        reconnectJob = null
        openSignal?.completeExceptionally(IllegalStateException("disconnected during connect"))
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        setState(GatewayState.DISCONNECTED)
    }

    fun ack(messageIds: List<com.google.protobuf.ByteString>) {
        val ackMsg = AckMessage.newBuilder()
            .addAllMessageIds(messageIds)
            .build()

        val frame = WebSocketFrame.newBuilder()
            .setAck(ackMsg)
            .build()

        webSocket?.send(ByteString.of(*frame.toByteArray()))
    }

    private fun openWebSocket(url: String, generation: Long) {
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (generation != connectionGeneration.get()) {
                    webSocket.close(1000, "Superseded connection")
                    return
                }
                setState(GatewayState.CONNECTED)
                reconnectAttempts = 0
                openSignal?.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (generation != connectionGeneration.get()) return
                try {
                    val frame = WebSocketFrame.parseFrom(bytes.toByteArray())
                    when {
                        frame.hasPreKeyStatus() -> preKeyStatus.trySend(frame.preKeyStatus)
                        frame.hasEnvelopeBatch() -> {
                            for (envelope in frame.envelopeBatch.envelopesList) {
                                envelopes.trySend(envelope)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Corrupt/undecodable frame on the wire: drop it and keep the socket
                    // alive. Non-fatal and self-limiting; the transport has no logger.
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (generation != connectionGeneration.get()) return
                this@GatewayConnection.webSocket = null
                setState(GatewayState.DISCONNECTED)
                openSignal?.completeExceptionally(t)
                if (shouldReconnect) scheduleReconnect()
            }

            // The server is closing (takeover, shutdown). OkHttp leaves the socket half-closed until
            // the close is answered, and only then calls onClosed.
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            // Only a close this client did not ask for reaches here: [disconnect] bumps the
            // generation first, so its own close is ignored above.
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (generation != connectionGeneration.get()) return
                this@GatewayConnection.webSocket = null
                setState(GatewayState.DISCONNECTED)
                openSignal?.completeExceptionally(
                    IllegalStateException("gateway closed before open: $code $reason")
                )
                if (shouldReconnect) scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            setState(GatewayState.RECONNECTING)

            // Exponential backoff: 1s, 2s, 4s, 8s, 16s, 30s max
            val delayMs = (1000L * (1L shl reconnectAttempts.coerceAtMost(5))).coerceAtMost(30_000L)
            delay(delayMs)
            reconnectAttempts++

            try {
                // Refresh token before reconnecting — stale token = failed ticket fetch
                ensureFreshToken()
                connect()
            } catch (_: Exception) {
                // connect() failed — will retry via onFailure → scheduleReconnect
                if (shouldReconnect) scheduleReconnect()
            }
        }
    }
}
