package dev.barrelmaker.obscura.kit.managers

import dev.barrelmaker.obscura.kit.crypto.AttachmentCrypto
import obscura.client.v1.Client.ClientMessage
import dev.barrelmaker.obscura.kit.ObscuraError
import dev.barrelmaker.obscura.kit.SendReport

/** The reference to an uploaded attachment: server id plus the key material to decrypt it. */
class AttachmentUpload(val id: String, val contentKey: ByteArray, val nonce: ByteArray)

/** Sends application entries; uploads and downloads attachments. */
internal class ContentService(
    private val ctx: ClientContext
) {
    private val session get() = ctx.session
    private val api get() = ctx.api
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
        acceptedDeviceIds: Set<String> = emptySet(),
    ): SendReport {
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
        val selfUserId = session.userId ?: throw ObscuraError.NotAuthenticated()
        val recipients = recipientUserIds.distinct().filter { it != selfUserId }
            .flatMap { messageSender.send(it, msg, acceptedDeviceIds) }
        // The author's other devices: the same path, which never targets this device.
        val selfSync = messageSender.send(selfUserId, msg, acceptedDeviceIds)
        return SendReport(recipients, selfSync)
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
