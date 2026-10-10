package dev.barrelmaker.obscura.kit.managers

import dev.barrelmaker.obscura.kit.crypto.AttachmentCrypto
import obscura.client.v1.Client.ClientMessage

/** The reference to an uploaded attachment: server id plus the key material to decrypt it. */
class AttachmentUpload(val id: String, val contentKey: ByteArray, val nonce: ByteArray)

/** Sends application entries; uploads and downloads attachments. */
internal class ContentService(
    private val ctx: ClientContext
) {
    private val session get() = ctx.session
    private val api get() = ctx.api
    private val messenger get() = ctx.messenger
    private val devices get() = ctx.devices
    private val messageSender get() = ctx.messageSender

    /**
     * Send an application entry to every device of the caller-named users plus the author's own
     * other devices. The sending device is excluded and gets no inbox row (pinned by
     * `EntrySendTests`).
     *
     * An empty `recipientUserIds` is valid and means "my own devices only".
     */
    suspend fun sendEntry(
        recipientUserIds: List<String>,
        modelKey: String,
        entryId: String,
        sentAt: Long,
        payload: ByteArray,
    ) {
        val msg = ClientMessage.newBuilder()
            .setTimestamp(System.currentTimeMillis())
            .setAppEntry(obscura.client.v1.appEntry {
                this.model = modelKey
                this.id = entryId
                timestamp = sentAt
                this.data = com.google.protobuf.ByteString.copyFrom(payload)
            }).build()

        // `distinct()` because the app may legitimately name the same user twice — e.g. both
        // participants of a canonical `userIdA_userIdB` conversation where one of them is you.
        val targets = recipientUserIds.distinct().filter { it != session.userId }

        // PER-RECIPIENT, not all-or-nothing. `sendToAllDevices` throws for a recipient with no
        // registered devices, so letting the first failure escape would abandon recipients 2..N —
        // and, worse, skip the own-device self-sync below, so the user's own other devices would
        // silently never receive something they wrote. One unreachable friend must not cost the
        // other four, or the sender's own copy.
        val failures = mutableListOf<Pair<String, Exception>>()
        val selfSyncFailures = mutableListOf<Pair<String, Exception>>()
        for (userId in targets) {
            try {
                messageSender.sendToAllDevices(userId, msg)
            } catch (e: Exception) {
                // Collected rather than logged: `ClientContext` carries no logger, and the detail is
                // more useful aggregated into the throw below than scattered across log lines.
                failures.add(userId to e)
            }
        }

        // Own OTHER devices. Runs whether or not a recipient failed — see above. Without the
        // filter this device encrypts to itself.
        val selfTargets = devices.getSelfSyncTargets().filter { it != session.deviceId }
        val selfUserId = session.userId
        if (selfTargets.isNotEmpty() && selfUserId != null) {
            // PER-DEVICE, and the flush is unconditional — the same rule as the recipient loop above,
            // for the same reason. One own device with a broken Signal session would otherwise abort
            // the loop, leave the messages already queued for the OTHER own devices unflushed, and
            // propagate out of `sendEntry`. The app then reports "reached nobody" for a send that
            // reached everybody, because `writeEntry` re-throws on a total failure.
            //
            // Swift already did this correctly (per-device `do/catch`, unconditional flush); this is
            // Kotlin catching up, not a new rule.
            for (devId in selfTargets) {
                try {
                    messenger.queueMessage(devId, msg, selfUserId)
                } catch (e: Exception) {
                    selfSyncFailures.add(devId to e)
                }
            }
            messenger.flushMessages()
        }

        // Throw only when NOBODY named got it. A partial failure is logged and survivable — the
        // entry is stored, the other recipients have it, and the caller can retry. A total failure
        // is different in kind: the app believes it sent something that reached no one, and it must
        // be able to tell the user so.
        if (targets.isNotEmpty() && failures.size == targets.size) {
            throw dev.barrelmaker.obscura.kit.ObscuraError.SendFailed(
                "$modelKey/${entryId.take(20)} reached none of its ${targets.size} recipient(s): " +
                    failures.joinToString { "${it.first.take(8)}=${it.second.message}" }
            )
        }
    }

    /**
     * Encrypt [plaintext] and upload the ciphertext. The server only ever sees ciphertext; the
     * caller embeds the returned reference in its encrypted entry payload. Mirrors the Swift kit.
     */
    suspend fun uploadAttachment(plaintext: ByteArray): AttachmentUpload {
        val encrypted = AttachmentCrypto.encrypt(plaintext)
        val id = api.uploadAttachment(encrypted.ciphertext)
        return AttachmentUpload(id = id, contentKey = encrypted.contentKey, nonce = encrypted.nonce)
    }

    suspend fun downloadDecryptedAttachment(id: String, contentKey: ByteArray, nonce: ByteArray): ByteArray =
        AttachmentCrypto.decrypt(api.fetchAttachment(id), contentKey, nonce)
}
