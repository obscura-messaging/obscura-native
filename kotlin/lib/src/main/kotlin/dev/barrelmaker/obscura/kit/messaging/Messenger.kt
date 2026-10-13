package dev.barrelmaker.obscura.kit.messaging

import com.google.protobuf.ByteString
import dev.barrelmaker.obscura.kit.crypto.SignalStore
import dev.barrelmaker.obscura.kit.crypto.UuidCodec
import dev.barrelmaker.obscura.kit.network.APIClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import obscura.client.v1.Client.*
import org.signal.libsignal.protocol.*
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import xyz.obscura.server.contracts.ObscuraProtocol.*
import java.util.*

data class DecryptedMessage(
    val clientMessage: ClientMessage,
    // The sending USER's UUID, taken from envelope.sender_id (Signal's source_service_id). This is
    // a routing/attribution HINT the server stamps: the recipient uses it to key the conversation
    // and to look up the display name in its OWN friend graph — it never trusts it as a display
    // name and never reads a user from the payload. The Signal session (selected by the
    // sending device below) is the actual authentication.
    val sourceUserId: String,
    // The sending device UUID is the address of the session that authenticated this message,
    // never a guessed registration id or user id.
    val senderDeviceId: String
)

/**
 * Signal sessions, encryption and transport submission for single devices. Which devices to
 * address, and when to fetch bundles, is [dev.barrelmaker.obscura.kit.managers.MessageSender]'s job.
 *
 * Every Signal session is keyed on the peer's device UUID, not registrationId. A
 * [SignalProtocolAddress] is a local store key; [addressFor] puts the UUID in its name and pins
 * the integer slot to [ADDR_DEVICE_ID]. Send and receive must build the same address.
 *
 * Signal state is confined to a single-threaded dispatcher. HTTP never runs on it: the receive
 * path shares it, so a slow request would stall decrypt, persist and ack.
 */
class Messenger internal constructor(
    private val signalStore: SignalStore,
    private val api: APIClient
) {
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    suspend fun hasSession(deviceId: String): Boolean = withContext(dispatcher) {
        signalStore.containsSession(addressFor(deviceId))
    }

    /**
     * Build an outbound session from [peer]'s bundle unless one already exists. Processing a bundle
     * over an established session would discard it and restart the ratchet, so an existing
     * session always wins, including one built concurrently by the receive path.
     */
    internal suspend fun establishSession(peer: PeerBundle) = withContext(dispatcher) {
        val address = addressFor(peer.deviceId)
        if (!signalStore.containsSession(address)) SessionBuilder(signalStore, address).process(peer.bundle)
    }

    /** Encrypt [message] for one device with an existing session. Never fetches a bundle. */
    internal suspend fun prepareSubmission(
        deviceId: String,
        message: ClientMessage,
    ): SendMessageRequest.Submission = withContext(dispatcher) {
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
            .setMessage(ByteString.copyFrom(encrypted.toByteArray()))
            .build()
    }

    /**
     * Upload [submissions] as one request and report each one. A transport or decoding failure
     * leaves every submission's acceptance unknown.
     */
    internal suspend fun submit(
        userId: String,
        submissions: List<SendMessageRequest.Submission>,
    ): List<Submitted> {
        if (submissions.isEmpty()) return emptyList()
        return try {
            val request = SendMessageRequest.newBuilder().addAllMessages(submissions).build()
            decodeSubmissionResults(userId, submissions, SendMessageResponse.parseFrom(api.sendMessage(request.toByteArray())))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            submissions.map { unknownSubmission(userId, it, e.message ?: e.javaClass.simpleName) }
        }
    }

    suspend fun decrypt(envelope: Envelope): DecryptedMessage = withContext(dispatcher) {
        // The USER hint. envelope.sender_id (Signal's source_service_id) is server-stamped from the
        // sender's account; we take the message's user from it and look up the display name in our
        // OWN friend graph — never from the payload. It is a routing/attribution hint,
        // NOT the trust root: the Signal session (selected by the device below) authenticates.
        val senderId = UuidCodec.bytesToUuid(envelope.senderId.toByteArray()).toString()

        // The server-stamped sender device UUID selects the pairwise inbound Signal session.
        // A SignalMessage carries no sender identity, so a missing UUID is an error, never a guess.
        val senderDeviceBytes = envelope.senderDeviceId.toByteArray()
        if (senderDeviceBytes.size != 16) {
            throw IllegalStateException(
                "Envelope from $senderId has no sender_device_id (${senderDeviceBytes.size} bytes); " +
                    "cannot select an inbound session"
            )
        }
        val senderDeviceUuid = UuidCodec.bytesToUuid(senderDeviceBytes).toString()

        val encMsg = EncryptedMessage.parseFrom(envelope.message.toByteArray())
        val content = encMsg.content.toByteArray()
        val cipher = SessionCipher(signalStore, addressFor(senderDeviceUuid))
        val decryptedBytes = if (encMsg.type == EncryptedMessage.Type.TYPE_PREKEY_MESSAGE) {
            cipher.decrypt(PreKeySignalMessage(content))
        } else {
            cipher.decrypt(SignalMessage(content))
        }

        // authorDeviceId is the address of the session that decrypted. A valid MAC proves
        // possession of that session's chain key, which only the sender's device has — so this
        // attribution is cryptographically sound, never a wire-field to be trusted blindly.
        DecryptedMessage(ClientMessage.parseFrom(decryptedBytes), senderId, senderDeviceUuid)
    }

    companion object {
        // The deviceId slot of every ProtocolAddress. Constant because the device UUID in the
        // name slot already uniquely identifies the peer device; libsignal just needs SOME
        // stable int here.
        private const val ADDR_DEVICE_ID = 1

        /**
         * THE single ProtocolAddress constructor for a peer device, used by BOTH send
         * (establishSession/prepareSubmission) and receive (decrypt). name = device UUID,
         * deviceId = constant. If send and receive ever built different addresses for the same
         * device the bidirectional session would split — this function is why they cannot.
         */
        fun addressFor(deviceUuid: String) = SignalProtocolAddress(deviceUuid, ADDR_DEVICE_ID)
    }
}
