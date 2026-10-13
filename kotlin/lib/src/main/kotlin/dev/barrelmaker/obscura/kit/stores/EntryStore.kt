package dev.barrelmaker.obscura.kit.stores

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.barrelmaker.obscura.kit.ModelEntry
import dev.barrelmaker.obscura.kit.db.ObscuraDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One stored entry. `data` and `localMetadata` are opaque strings the kit never parses.
 *
 * `sentAt` and `authorDeviceId` are carried because the app's merge needs them — REPLACE is a total
 * order on `(sentAt, authorDeviceId)`. They are metadata in columns beside the
 * payload, not fields the kit reads out of it.
 *
 * `localMetadata` is app-owned local-only bookkeeping. It is persisted beside the entry and is
 * never serialized into `AppEntry` or sent to another device.
 */
data class StoredEntry(
    val id: String,
    val data: String,
    val sentAt: Long,
    val authorDeviceId: String,
    val localMetadata: String? = null,
)

/**
 * Raw storage for application entries.
 *
 * The API is `put` / `get` / `all` / `erase`. `put` is a blind upsert; the app resolves merge
 * before writing. This store has no schema parser, query layer, merge engine, or expiry policy: the
 * app decides *when* an entry goes away, and `erase` guarantees *how*.
 */
class EntryStore internal constructor(
    private val db: ObscuraDatabase,
    private val driver: SqlDriver,
) {
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    /**
     * Write an entry, replacing any existing one with the same `(model, id)`.
     *
     * `data` is stored verbatim and not validated as JSON: the kit may not read its shape.
     */
    suspend fun put(model: String, entry: StoredEntry) = withContext(dispatcher) {
        db.modelEntryQueries.insertEntry(
            model_name = model,
            entry_id = entry.id,
            data_ = entry.data,
            timestamp = entry.sentAt,
            author_device_id = entry.authorDeviceId,
            local_metadata = entry.localMetadata,
        )
    }

    /** The live entries among [ids] for a model, in no guaranteed order. Missing ids are absent. */
    suspend fun get(model: String, ids: Collection<String>): List<StoredEntry> = withContext(dispatcher) {
        if (ids.isEmpty()) emptyList()
        else db.modelEntryQueries.selectByIds(model, ids).executeAsList().map { it.toStoredEntry() }
    }

    /** Every live entry for a model, in no guaranteed order. */
    suspend fun all(model: String): List<StoredEntry> = withContext(dispatcher) {
        db.modelEntryQueries.selectByModel(model).executeAsList().map { it.toStoredEntry() }
    }

    /**
     * Remove entries so their contents are unrecoverable from the database files.
     *
     * The rows are deleted under `secure_delete`, so SQLite zeroes the freed pages. The pragma is
     * per-connection and drivers may pool connections, so it is set inside the deleting transaction
     * rather than trusted from open time. The WAL is then checkpointed and truncated once, because
     * in WAL mode the pre-delete pages would otherwise survive in the `-wal` file until the next
     * checkpoint. Erasing entries that do not exist is a no-op. Not synchronized to peers: each
     * device erases its own copy.
     */
    suspend fun erase(model: String, ids: Collection<String>) = withContext(dispatcher) {
        if (ids.isEmpty()) return@withContext
        db.transaction {
            driver.pragma("PRAGMA secure_delete = ON")
            db.modelEntryQueries.deleteEntries(model, ids)
        }
        driver.pragma("PRAGMA wal_checkpoint(TRUNCATE)")
    }

    private fun ModelEntry.toStoredEntry() = StoredEntry(
        id = entry_id,
        data = data_,
        sentAt = timestamp,
        authorDeviceId = author_device_id,
        localMetadata = local_metadata,
    )
}

/**
 * Run a PRAGMA through `executeQuery`: several PRAGMAs return a row, which Android's
 * `execute` path rejects.
 */
internal fun SqlDriver.pragma(sql: String) {
    executeQuery(null, sql, { cursor -> cursor.next(); QueryResult.Unit }, 0)
}
