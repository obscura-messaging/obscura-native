package dev.barrelmaker.obscura.kit.messaging

import com.google.protobuf.ByteString
import dev.barrelmaker.obscura.kit.crypto.SignalStore
import dev.barrelmaker.obscura.kit.crypto.UuidCodec
import dev.barrelmaker.obscura.kit.network.APIClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import obscura.client.v1.Client.ClientMessage
import obscura.client.v1.Client.EncryptedMessage
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import xyz.obscura.server.contracts.ObscuraProtocol.SendMessageRequest
import xyz.obscura.server.contracts.ObscuraProtocol.SendMessageResponse
import java.util.UUID

/**
 * Signal sessions and encryption for single devices, keyed by the peer's device UUID. Signal state
 * is confined to one thread; HTTP never runs on it, so a slow request cannot stall decryption.
 */
internal class Messenger(private val signalStore: SignalStore, private val api: APIClient) {
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)

    suspend fun hasSession(deviceId: String): Boolean = withContext(dispatcher) {
        signalStore.containsSession(addressFor(deviceId))
    }

    /** Processing a bundle over an existing session would restart its ratchet, so an existing one wins. */
    suspend fun establishSession(peer: PeerBundle) = withContext(dispatcher) {
        val address = addressFor(peer.deviceId)
        if (!signalStore.containsSession(address)) SessionBuilder(signalStore, address).process(peer.bundle)
    }

    suspend fun prepareSubmission(deviceId: String, message: ClientMessage): SendMessageRequest.Submission =
        withContext(dispatcher) {
            val address = addressFor(deviceId)
            check(signalStore.containsSession(address)) { "No session with device $deviceId" }
            val ciphertext = SessionCipher(signalStore, address).encrypt(message.toByteArray())
            val encrypted = EncryptedMessage.newBuilder()
                .setType(
                    if (ciphertext.type == CiphertextMessage.PREKEY_TYPE) EncryptedMessage.Type.TYPE_PREKEY_MESSAGE
                    else EncryptedMessage.Type.TYPE_ENCRYPTED_MESSAGE
                )
                .setContent(ByteString.copyFrom(ciphertext.serialize()))
                .build()
            SendMessageRequest.Submission.newBuilder()
                .setSubmissionId(UuidCodec.uuidToBytes(UUID.randomUUID()))
                .setDeviceId(UuidCodec.uuidToBytes(UUID.fromString(deviceId)))
                .setMessage(encrypted.toByteString())
                .build()
        }

    /** Upload [submissions] as one request. A transport failure leaves every result unknown. */
    suspend fun submit(userId: String, submissions: List<SendMessageRequest.Submission>): List<Submitted> {
        if (submissions.isEmpty()) return emptyList()
        return try {
            val request = SendMessageRequest.newBuilder().addAllMessages(submissions).build()
            decodeSubmissionResults(userId, submissions, SendMessageResponse.parseFrom(api.sendMessage(request.toByteArray())))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            submissions.map { unknownSubmission(userId, it, e.message ?: e.javaClass.simpleName) }
        }
    }

    /** Decrypt with the session of [senderDeviceId]. A valid MAC authenticates that device. */
    suspend fun decrypt(senderDeviceId: String, message: ByteString): ByteArray = withContext(dispatcher) {
        val encrypted = EncryptedMessage.parseFrom(message)
        val content = encrypted.content.toByteArray()
        val cipher = SessionCipher(signalStore, addressFor(senderDeviceId))
        if (encrypted.type == EncryptedMessage.Type.TYPE_PREKEY_MESSAGE) cipher.decrypt(PreKeySignalMessage(content))
        else cipher.decrypt(SignalMessage(content))
    }

    companion object {
        /** Send and receive must build the same address or a session splits in two. */
        fun addressFor(deviceUuid: String) = SignalProtocolAddress(deviceUuid, 1)
    }
}
