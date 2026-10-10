package dev.barrelmaker.obscura.kit.messaging

import com.google.protobuf.ByteString
import dev.barrelmaker.obscura.kit.crypto.SignalStore
import dev.barrelmaker.obscura.kit.crypto.UuidCodec
import dev.barrelmaker.obscura.kit.crypto.fromBase64
import dev.barrelmaker.obscura.kit.network.APIClient
import dev.barrelmaker.obscura.kit.stores.FriendData
import dev.barrelmaker.obscura.kit.stores.FriendDeviceInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import obscura.client.v1.Client.*
import org.json.JSONArray
import org.signal.libsignal.protocol.*
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
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
 * Messenger - Confined coroutines. Encrypt/decrypt/queue/flush.
 * Single-threaded dispatcher protects Signal ratchet state.
 *
 * Every Signal session is keyed on the peer's device UUID, not registrationId. A
 * [SignalProtocolAddress] is a local store key; [addressFor] puts the UUID in its name and pins
 * the integer slot to [ADDR_DEVICE_ID]. Send and receive must build the same address.
 */
class Messenger internal constructor(
    private val signalStore: SignalStore,
    private val api: APIClient
) {
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    // device UUID -> owning user. Signal registration IDs stay inside Signal bundle/session state.
    private val deviceMap = mutableMapOf<String, String>()

    // Pending submissions for batch sending
    private val queue = mutableListOf<SendMessageRequest.Submission>()

    fun mapDevice(deviceId: String, userId: String) {
        deviceMap[deviceId] = userId
    }

    fun deviceMap(deviceId: String): String? = deviceMap[deviceId]

    fun getDeviceIdsForUser(userId: String): List<String> {
        return deviceMap.entries.filter { it.value == userId }.map { it.key }
    }

    /**
     * Snapshot the devices we currently know for [userId] (learned from prekey fetches / the friend
     * graph) as [FriendDeviceInfo], so a caller can PERSIST them into the friend record. That is
     * what makes the device -> user mapping DURABLE across a restart: on the next connect,
     * rebuildDeviceMap(getAccepted()) restores deviceMap from the persisted friend.devices. Without
     * this, deviceMap is in-memory only and a friend's messages become unattributable after restart.
     */
    fun knownDevicesFor(userId: String): List<FriendDeviceInfo> =
        deviceMap.entries.filter { it.value == userId }.map {
            FriendDeviceInfo(
                id = it.key,
                name = "",
            )
        }

    fun rebuildDeviceMap(friends: List<FriendData>) {
        for (friend in friends) {
            for (device in friend.devices) {
                deviceMap[device.id] = friend.userId
            }
        }
    }

    /**
     * ## Why the dispatcher is entered twice instead of wrapping the whole body
     *
     * [dispatcher] is a `limitedParallelism(1)` confining the Signal ratchet — the Kotlin stand-in
     * for a Swift actor, and correct for that. What it must NOT confine is HTTP. Everything on this
     * dispatcher is serialised, receive path included, so HTTP retries inside it would block
     * decrypt/persist/ack. Only libsignal work is confined; network calls happen outside.
     */
    suspend fun queueMessage(targetDeviceId: String, message: ClientMessage, userId: String? = null) {
        // targetDeviceId IS the peer's device UUID — the address name slot (see addressFor).
        // The owning userId is needed only to fetch that device's prekey bundle on first
        // contact; it is NOT part of the session address.
        val ownerUserId = userId ?: withContext(dispatcher) { deviceMap[targetDeviceId] }
            ?: throw IllegalStateException("No owning userId known for device $targetDeviceId")

        val clientMsgBytes = message.toByteArray()
        ensureSession(ownerUserId, targetDeviceId)

        withContext(dispatcher) {
            val encrypted = encrypt(targetDeviceId, clientMsgBytes)

            val encMsg = EncryptedMessage.newBuilder()
                .setType(
                    if (encrypted.first == 3) EncryptedMessage.Type.TYPE_PREKEY_MESSAGE
                    else EncryptedMessage.Type.TYPE_ENCRYPTED_MESSAGE
                )
                .setContent(ByteString.copyFrom(encrypted.second))
                .build()

            val submission = SendMessageRequest.Submission.newBuilder()
                .setSubmissionId(UuidCodec.uuidToBytes(UUID.randomUUID()))
                .setDeviceId(UuidCodec.uuidToBytes(UUID.fromString(targetDeviceId)))
                .setMessage(ByteString.copyFrom(encMsg.toByteArray()))
                .build()

            queue.add(submission)
        }
    }

    suspend fun flushMessages(): Triple<Int, Int, List<SendMessageResponse.FailedSubmission>> {
        // Take the batch under the dispatcher; POST it outside. Only the queue needs confining.
        val submissions = withContext(dispatcher) {
            queue.toList().also { queue.clear() }
        }
        if (submissions.isEmpty()) return Triple(0, 0, emptyList())

        val request = SendMessageRequest.newBuilder()
            .addAllMessages(submissions)
            .build()

        val responseBytes = try {
            api.sendMessage(request.toByteArray())
        } catch (e: Exception) {
            // Network failure — re-queue for retry
            withContext(dispatcher) { queue.addAll(submissions) }
            throw e
        }

        val failedSubmissions = if (responseBytes.isNotEmpty()) {
            try {
                SendMessageResponse.parseFrom(responseBytes).failedSubmissionsList
            } catch (e: Exception) {
                // Can't parse response — treat as all failed, re-queue
                withContext(dispatcher) { queue.addAll(submissions) }
                return Triple(0, submissions.size, emptyList())
            }
        } else {
            emptyList()
        }

        return Triple(
            submissions.size - failedSubmissions.size,
            failedSubmissions.size,
            failedSubmissions
        )
    }

    // Fetches and processes a user's prekey bundles as a side effect: populates deviceMap so
    // getDeviceIdsForUser can enumerate the user's devices for fan-out. Callers use it for that
    // side effect, not the return value.
    suspend fun fetchPreKeyBundles(userId: String): List<PreKeyBundle> {
        val bundlesJson = api.fetchPreKeyBundles(userId)
        return withContext(dispatcher) { parsePreKeyBundles(bundlesJson, userId).map { it.second } }
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
        val isPreKey = encMsg.type == EncryptedMessage.Type.TYPE_PREKEY_MESSAGE
        val content = encMsg.content.toByteArray()

        val address = addressFor(senderDeviceUuid)
        val cipher = SessionCipher(signalStore, address)
        val decryptedBytes = if (isPreKey) {
            cipher.decrypt(PreKeySignalMessage(content))
        } else {
            cipher.decrypt(SignalMessage(content))
        }
        val clientMsg = ClientMessage.parseFrom(decryptedBytes)

        // Learn the sender's device for later fan-out.
        deviceMap.putIfAbsent(senderDeviceUuid, senderId)

        // authorDeviceId is the address of the session that decrypted. A valid MAC proves
        // possession of that session's chain key, which only the sender's device has — so this
        // attribution is cryptographically sound, never a wire-field to be trusted blindly.
        DecryptedMessage(clientMsg, senderId, senderDeviceUuid)
    }

    private suspend fun ensureSession(targetUserId: String, targetDeviceUuid: String) {
        val address = addressFor(targetDeviceUuid)
        if (withContext(dispatcher) { signalStore.containsSession(address) }) return

        // The HTTP fetch is deliberately outside the dispatcher — see [queueMessage].
        val bundlesJson = api.fetchPreKeyBundles(targetUserId)

        withContext(dispatcher) {
            val bundles = parsePreKeyBundles(bundlesJson, targetUserId)
            // Select by device UUID. Never fall back to another device's keys.
            val bundle = bundles.firstOrNull { it.first == targetDeviceUuid }?.second
                ?: throw IllegalStateException(
                    "No prekey bundle for device $targetDeviceUuid of user $targetUserId"
                )
            // Re-checked inside the confined section: two concurrent sends to the same device both
            // saw "no session" above and both fetched. Building twice would process a second bundle
            // over an established session and reset the ratchet, so the loser must no-op.
            if (!signalStore.containsSession(address)) {
                SessionBuilder(signalStore, address).process(bundle)
            }
        }
    }

    private fun encrypt(targetDeviceUuid: String, plaintext: ByteArray): Pair<Int, ByteArray> {
        val cipher = SessionCipher(signalStore, addressFor(targetDeviceUuid))
        val ciphertext = cipher.encrypt(plaintext)
        return Pair(ciphertext.type, ciphertext.serialize())
    }

    // Returns (deviceUuid, PreKeyBundle) pairs and populates deviceMap as a side effect.
    private fun parsePreKeyBundles(bundlesJson: JSONArray, userId: String): List<Pair<String, PreKeyBundle>> {
        return (0 until bundlesJson.length()).map { i ->
            val b = bundlesJson.getJSONObject(i)
            val deviceId = b.getString("deviceId")
            val regId = b.getInt("registrationId")
            deviceMap[deviceId] = userId

            val spk = b.getJSONObject("signedPreKey")
            val otp = if (b.has("oneTimePreKey") && !b.isNull("oneTimePreKey")) b.getJSONObject("oneTimePreKey") else null

            deviceId to PreKeyBundle(
                regId, 1,
                otp?.getInt("keyId") ?: 0,
                otp?.let { Curve.decodePoint(it.getString("publicKey").fromBase64(), 0) },
                spk.getInt("keyId"),
                Curve.decodePoint(spk.getString("publicKey").fromBase64(), 0),
                spk.getString("signature").fromBase64(),
                IdentityKey(b.getString("identityKey").fromBase64())
            )
        }
    }

    companion object {
        // The deviceId slot of every ProtocolAddress. Constant because the device UUID in the
        // name slot already uniquely identifies the peer device; libsignal just needs SOME
        // stable int here.
        private const val ADDR_DEVICE_ID = 1

        /**
         * THE single ProtocolAddress constructor for a peer device, used by BOTH send
         * (queueMessage/ensureSession/encrypt) and receive (decrypt). name = device UUID,
         * deviceId = constant. If send and receive ever built different addresses for the same
         * device the bidirectional session would split — this function is why they cannot.
         */
        fun addressFor(deviceUuid: String) = SignalProtocolAddress(deviceUuid, ADDR_DEVICE_ID)
    }
}
