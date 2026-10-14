package dev.barrelmaker.obscura.kit.stores

import app.cash.sqldelight.coroutines.asFlow
import dev.barrelmaker.obscura.kit.db.ObscuraDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

enum class FriendStatus(val value: String) {
    PENDING_SENT("pending_sent"),
    PENDING_RECEIVED("pending_received"),
    ACCEPTED("accepted"),
}

data class FriendData(val userId: String, val username: String, val status: FriendStatus)

internal class FriendStore(private val db: ObscuraDatabase) {
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val queries = db.friendQueries

    /** Emits once on collection and after every change to the friend table. */
    val changes: Flow<Unit> = queries.selectAll(::friend).asFlow().map { }

    suspend fun add(userId: String, username: String, status: FriendStatus) = withContext(dispatcher) {
        addInTransaction(userId, username, status)
    }

    suspend fun get(userId: String): FriendData? = withContext(dispatcher) {
        getInTransaction(userId)
    }

    internal fun addInTransaction(userId: String, username: String, status: FriendStatus) =
        queries.insert(userId, username, status.value)

    internal fun getInTransaction(userId: String): FriendData? =
        queries.selectById(userId, ::friend).executeAsOneOrNull()

    internal fun updateStatusInTransaction(userId: String, status: FriendStatus) =
        queries.updateStatus(status.value, userId)

    suspend fun getAll(): List<FriendData> = withContext(dispatcher) { queries.selectAll(::friend).executeAsList() }

    suspend fun prepareRequest(userId: String, username: String): FriendData? = withContext(dispatcher) {
        db.transactionWithResult {
            queries.selectById(userId, ::friend).executeAsOneOrNull().also {
                if (it == null) queries.insert(userId, username, FriendStatus.PENDING_SENT.value)
            }
        }
    }

    suspend fun removePendingRequest(userId: String) = withContext(dispatcher) {
        queries.deleteIfStatus(userId, FriendStatus.PENDING_SENT.value)
    }

    /** Changes the status only. A status change driven by a peer must never carry a name with it. */
    suspend fun updateStatus(userId: String, status: FriendStatus) = withContext(dispatcher) {
        updateStatusInTransaction(userId, status)
    }

    suspend fun remove(userId: String) = withContext(dispatcher) { queries.deleteById(userId) }

    private fun friend(userId: String, username: String, status: String) =
        FriendData(userId, username, FriendStatus.entries.first { it.value == status })
}
