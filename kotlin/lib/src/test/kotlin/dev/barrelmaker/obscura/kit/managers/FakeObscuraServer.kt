package dev.barrelmaker.obscura.kit.managers

import com.google.protobuf.ByteString
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.barrelmaker.obscura.kit.crypto.SignalStore
import dev.barrelmaker.obscura.kit.crypto.UuidCodec
import dev.barrelmaker.obscura.kit.crypto.toBase64
import dev.barrelmaker.obscura.kit.messaging.Messenger
import dev.barrelmaker.obscura.kit.network.APIClient
import dev.barrelmaker.obscura.kit.newInMemoryDatabase
import dev.barrelmaker.obscura.kit.stores.DeviceStore
import obscura.client.v1.Client.ClientMessage
import obscura.client.v1.Client.EncryptedMessage
import org.json.JSONArray
import org.json.JSONObject
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.state.PreKeyRecord
import xyz.obscura.server.contracts.ObscuraProtocol.Envelope
import xyz.obscura.server.contracts.ObscuraProtocol.SendMessageRequest
import xyz.obscura.server.contracts.ObscuraProtocol.SendMessageResponse
import java.net.InetSocketAddress
import java.util.Collections
import java.util.UUID

/**
 * An in-process stand-in for the bundle and message endpoints, with the real server's semantics:
 * `GET /v1/users/{id}` returns one bundle per device and consumes one one-time prekey from each
 * (404 when the user has none), and `POST /v1/messages` rejects a submission for a nonexistent
 * device with `ERROR_CODE_INVALID_DEVICE`. Accepted submissions land in the target device's mailbox.
 */
class FakeObscuraServer : AutoCloseable {
    private val devices = Collections.synchronizedMap(linkedMapOf<String, FakeDevice>())
    private val fetches = Collections.synchronizedMap(mutableMapOf<String, Int>())
    private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1/users/") { respond(it, bundles(it.requestURI.path.removePrefix("/v1/users/"))) }
        createContext("/v1/messages") { respond(it, accept(it)) }
        start()
    }
    val url = "http://127.0.0.1:${http.address.port}"

    /** Bundle fetches made for [userId], each of which consumed a prekey on every device. */
    fun bundleFetches(userId: String) = fetches[userId] ?: 0

    fun register(userId: String, corruptBundle: Boolean = false) =
        FakeDevice(this, userId, corruptBundle).also { devices[it.deviceId] = it }

    fun remove(device: FakeDevice) { devices.remove(device.deviceId) }

    override fun close() = http.stop(0)

    private fun bundles(userId: String): Pair<Int, ByteArray> {
        fetches.merge(userId, 1, Int::plus)
        val owned = devices.values.filter { it.userId == userId }
        if (owned.isEmpty()) return 404 to ByteArray(0)
        return 200 to JSONArray(owned.map { it.consumeBundle() }).toString().toByteArray()
    }

    private fun accept(exchange: HttpExchange): Pair<Int, ByteArray> {
        val sender = devices.getValue(exchange.requestHeaders.getFirst("Authorization").removePrefix("Bearer "))
        val request = SendMessageRequest.parseFrom(exchange.requestBody.readBytes())
        val response = SendMessageResponse.newBuilder()
        for (submission in request.messagesList) {
            val target = devices[UuidCodec.uuidString(submission.deviceId)]
            if (target == null) {
                response.addFailedSubmissions(SendMessageResponse.FailedSubmission.newBuilder()
                    .setSubmissionId(submission.submissionId)
                    .setErrorCode(SendMessageResponse.ErrorCode.ERROR_CODE_INVALID_DEVICE))
            } else {
                target.mailbox.add(Envelope.newBuilder()
                    .setId(UuidCodec.uuidToBytes(UUID.randomUUID()))
                    .setSenderId(UuidCodec.uuidToBytes(UUID.fromString(sender.userId)))
                    .setSenderDeviceId(UuidCodec.uuidToBytes(UUID.fromString(sender.deviceId)))
                    .setMessage(submission.message)
                    .build())
            }
        }
        return 200 to response.build().toByteArray()
    }

    private fun respond(exchange: HttpExchange, reply: Pair<Int, ByteArray>) {
        val (status, body) = reply
        exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }
}

/** A real Signal device: its own store, keys, directory and [MessageSender], on [FakeObscuraServer]. */
class FakeDevice internal constructor(
    server: FakeObscuraServer,
    val userId: String,
    private val corruptBundle: Boolean,
) {
    val deviceId: String = UUID.randomUUID().toString()
    internal val signalStore = SignalStore(newInMemoryDatabase())
    internal val directory = DeviceStore(newInMemoryDatabase())
    internal val messenger: Messenger
    internal val sender: MessageSender
    val mailbox: MutableList<Envelope> = Collections.synchronizedList(mutableListOf())
    private val unusedPreKeys = ArrayDeque<PreKeyRecord>()

    init {
        val api = APIClient(server.url) { deviceId }
        messenger = Messenger(signalStore, api)
        sender = MessageSender(api, messenger, directory) { deviceId }
        rekey()
    }

    /** Replace this device's identity and prekeys, as a reinstall does. */
    fun rekey() {
        val (identity, _) = signalStore.generateIdentity()
        SignalKeyUtils.generateSignedPreKey(signalStore, identity, 1)
        unusedPreKeys.clear()
        unusedPreKeys.addAll(SignalKeyUtils.generateOneTimePreKeys(signalStore, 1, 100))
    }

    val remainingPreKeys get() = unusedPreKeys.size

    internal fun consumeBundle(): JSONObject {
        val spk = signalStore.loadSignedPreKey(1)
        val otpk = unusedPreKeys.removeFirstOrNull()
        val signature = if (corruptBundle) ByteArray(64) else spk.signature
        return JSONObject()
            .put("deviceId", deviceId)
            .put("registrationId", signalStore.localRegistrationId)
            .put("identityKey", signalStore.identityKeyPair.publicKey.serialize().toBase64())
            .put("signedPreKey", JSONObject().put("keyId", 1)
                .put("publicKey", spk.keyPair.publicKey.serialize().toBase64())
                .put("signature", signature.toBase64()))
            .put("oneTimePreKey", otpk?.let { JSONObject().put("keyId", it.id).put("publicKey", it.keyPair.publicKey.serialize().toBase64()) } ?: JSONObject.NULL)
    }

    /** Decrypt and clear the mailbox. */
    suspend fun receive(): List<Received> = mailbox.toList().also { mailbox.clear() }.map { envelope ->
        val encrypted = EncryptedMessage.parseFrom(envelope.message)
        Received(
            messenger.decrypt(UuidCodec.uuidString(envelope.senderDeviceId)!!, envelope.message),
            encrypted.type,
            if (encrypted.type == EncryptedMessage.Type.TYPE_PREKEY_MESSAGE) {
                ByteString.copyFrom(PreKeySignalMessage(encrypted.content.toByteArray()).baseKey.serialize())
            } else null,
        )
    }
}

/** A decrypted delivery. [baseKey] identifies the session a prekey message starts; null otherwise. */
data class Received(val message: ClientMessage, val type: EncryptedMessage.Type, val baseKey: ByteString?)
