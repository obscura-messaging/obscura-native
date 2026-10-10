package dev.barrelmaker.obscura.kit.stores

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.barrelmaker.obscura.kit.db.ObscuraDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One stored entry. `data` and `localMetadata` are opaque strings the kit never parses.
 *
 * `sentAt` and `authorDeviceId` are carried because the app's merge needs them — REPLACE is a total
 * order on `(sentAt, authorDeviceId)` (`KIT_API.md` §8.2). They are metadata in columns beside the
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
 * Raw storage for application entries (`KIT_API.md` §8.1).
 *
 * The other half of the thin kit's app-facing surface: `InboxStore` is how messages arrive,
 * this is where the app keeps what it made of them. Together they are the whole data path.
 *
 * The API is `put` / `all` / `erase`. `put` is a blind upsert; the app resolves merge before
 * writing. This store has no schema parser, query layer, merge engine, or expiry policy: the app
 * decides *when* an entry goes away, and `erase` guarantees *how*.
 * `all(model)` therefore loads every live entry for that model.
 */
class EntryStore internal constructor(
    private val db: ObscuraDatabase,
    private val driver: SqlDriver,
) {
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    /**
     * Write an entry, replacing any existing one with the same `(model, id)`.
     *
     * Blind by design — see the class doc. `data` is stored verbatim; the kit does not validate it
     * as JSON, because validating a shape it may not read is a boundary violation dressed as
     * defensiveness (SPEC §0.4).
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

    /** Every live entry for a model, in no guaranteed order. */
    suspend fun all(model: String): List<StoredEntry> = withContext(dispatcher) {
        db.modelEntryQueries.selectByModel(model).executeAsList().map { row ->
            StoredEntry(
                id = row.entry_id,
                data = row.data_,
                sentAt = row.timestamp,
                authorDeviceId = row.author_device_id,
                localMetadata = row.local_metadata,
            )
        }
    }

    /**
     * Remove one entry so its contents are unrecoverable from the database files.
     *
     * The row is deleted under `secure_delete`, so SQLite zeroes the freed pages. The pragma is
     * per-connection and drivers may pool connections, so it is set inside the deleting transaction
     * rather than trusted from open time. The WAL is then checkpointed and truncated, because in WAL
     * mode the pre-delete page would otherwise survive in the `-wal` file until the next checkpoint.
     * Erasing an entry that does not exist is a no-op. Not synchronized to peers: each device
     * erases its own copy.
     */
    suspend fun erase(model: String, id: String) = withContext(dispatcher) {
        db.transaction {
            driver.pragma("PRAGMA secure_delete = ON")
            db.modelEntryQueries.deleteEntry(model, id)
        }
        driver.pragma("PRAGMA wal_checkpoint(TRUNCATE)")
    }
}

/**
 * Run a PRAGMA through `executeQuery`: several PRAGMAs return a row, which Android's
 * `execute` path rejects.
 */
internal fun SqlDriver.pragma(sql: String) {
    executeQuery(null, sql, { cursor -> cursor.next(); QueryResult.Unit }, 0)
}
