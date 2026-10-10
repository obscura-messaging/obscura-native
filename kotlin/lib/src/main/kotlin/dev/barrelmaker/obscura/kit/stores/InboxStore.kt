package dev.barrelmaker.obscura.kit.stores

import dev.barrelmaker.obscura.kit.db.ObscuraDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One drained inbox row, as the app sees it.
 *
 * `payload` is opaque bytes the kit never parsed. The AppEntry-derived fields are null for every
 * other kind, including an unknown arm — there is no AppEntry to derive them from.
 */
data class InboxRecord(
    val id: Long,
    val kind: String,
    val senderUserId: String,
    val senderDeviceId: String?,
    val modelKey: String?,
    val entryId: String?,
    val sentAt: Long?,
    val payload: ByteArray,
) {
    // ByteArray gives reference equality from the data-class defaults, which silently breaks any
    // assertEquals on a record. Spelled out rather than left to surprise someone in a test.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InboxRecord) return false
        return id == other.id && kind == other.kind &&
            senderUserId == other.senderUserId && senderDeviceId == other.senderDeviceId &&
            modelKey == other.modelKey && entryId == other.entryId && sentAt == other.sentAt &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = 31 * id.hashCode() + payload.contentHashCode()
}

internal data class InboxInsert(
    val envelopeId: String,
    val kind: String,
    val senderUserId: String,
    val senderDeviceId: String?,
    val modelKey: String?,
    val entryId: String?,
    val sentAt: Long?,
    val payload: ByteArray,
)

/**
 * The durable inbox: opaque payloads persisted before the server copy is acked, because the app
 * may not be running to receive an event. Only the receive path writes it; the app drains it.
 */
class InboxStore internal constructor(private val db: ObscuraDatabase) {
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    /**
     * Persist a decrypted message. **Kit-internal**: called from the receive loop before the ack,
     * never by the app.
     *
     * Throws if the write fails, which is the point — the caller must not ack what is not stored.
     *
     * Returns true if a row was inserted, false if `envelopeId` was already present. A false is a
     * *successful* redelivery absorption, not an error: it means the message is already durably
     * held, so the caller should ack exactly as it would after a fresh insert. Acking is what stops
     * the server sending it a third time.
     */
    internal suspend fun put(record: InboxInsert): Boolean = withContext(dispatcher) {
        val existedBefore = db.inboxQueries.existsByEnvelopeId(record.envelopeId).executeAsOne()
        db.inboxQueries.insertRow(
            record.envelopeId, record.kind, record.senderUserId,
            record.senderDeviceId, record.modelKey, record.entryId,
            record.sentAt, record.payload,
        )

        // Assert the postcondition the ack depends on, rather than inferring it from a row count.
        //
        // `changes()` cannot distinguish an envelope-id duplicate from another ignored constraint
        // violation. Check the required row directly before allowing the caller to acknowledge it.
        if (!db.inboxQueries.existsByEnvelopeId(record.envelopeId).executeAsOne()) {
            throw IllegalStateException(
                "inbox row for envelope ${record.envelopeId} is absent after insert; refusing to " +
                    "report it as stored"
            )
        }
        !existedBefore
    }

    /**
     * The next rows to process, in drain order, oldest first.
     *
     * **Side-effect free.** Peeking twice without consuming returns the same rows — that is the
     * crash-safety property, not a bug: an app that dies mid-drain reprocesses, and the merge rules
     * downstream are idempotent so that reprocessing converges.
     */
    suspend fun peek(limit: Int = 50): List<InboxRecord> = withContext(dispatcher) {
        db.inboxQueries.peek(limit.toLong()).executeAsList().map { row ->
            InboxRecord(
                id = row.id,
                kind = row.kind,
                senderUserId = row.sender_user_id,
                senderDeviceId = row.sender_device_id,
                modelKey = row.model_key,
                entryId = row.entry_id,
                sentAt = row.sent_at,
                payload = row.payload,
            )
        }
    }

    /**
     * Drop rows the app has durably processed.
     *
     * Idempotent, and a subset is fine — partial progress is normal, not an error path.
     */
    suspend fun consume(ids: List<Long>) = withContext(dispatcher) {
        // Chunked because `WHERE id IN ?` binds one variable per id, and SQLite caps that at 999 on
        // older builds. The app chooses the batch size, so `peek(limit = 5000)` then `consume` of
        // 5000 ids would throw "too many SQL variables" — and it would throw exactly when a large
        // backlog exists, i.e. the one situation where the drain must not stall.
        ids.chunked(DELETE_CHUNK).forEach { db.inboxQueries.deleteByIds(it) }
    }

    /**
     * Drop rows the app declares it can **never** process.
     *
     * This is deliberate data loss: the server's copy is already gone. It is separate from
     * [consume] so it can be logged as a security-relevant event.
     */
    suspend fun discard(ids: List<Long>, reason: String): List<Long> = withContext(dispatcher) {
        if (ids.isEmpty()) return@withContext emptyList()
        ids.chunked(DELETE_CHUNK).forEach { db.inboxQueries.deleteByIds(it) }
        onDiscard?.invoke(ids, reason)
        ids
    }

    private companion object {
        /** Comfortably under SQLite's 999-variable floor. */
        const val DELETE_CHUNK = 500
    }

    /** Set by the client so a discard reaches the security log rather than vanishing. */
    internal var onDiscard: ((List<Long>, String) -> Unit)? = null

    /**
     * How many rows are waiting.
     *
     * Unbounded growth means the app has stopped draining. The app must surface abnormal depth
     * before disk pressure prevents persistence and moves the backlog to the bounded server queue.
     */
    suspend fun depth(): Long = withContext(dispatcher) {
        db.inboxQueries.depth().executeAsOne()
    }

    /**
     * Destroy every row.
     *
     * For device wipe only, so decrypted plaintext can be destroyed. It takes no selector so it
     * cannot become an eviction policy.
     */
    internal suspend fun wipe() = withContext(dispatcher) {
        db.inboxQueries.deleteAll()
    }
}
