package dev.barrelmaker.obscura.kit.stores

import dev.barrelmaker.obscura.kit.Friend
import dev.barrelmaker.obscura.kit.db.ObscuraDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class FriendStatus(val value: String) {
    PENDING_SENT("pending_sent"),
    PENDING_RECEIVED("pending_received"),
    ACCEPTED("accepted")
}

data class FriendData(
    val userId: String,
    val username: String,
    val status: FriendStatus,
)

/** The one mapping from a stored friend row. */
internal fun Friend.toFriendData() = FriendData(
    userId = user_id,
    username = username,
    status = FriendStatus.entries.find { it.value == status } ?: FriendStatus.PENDING_SENT,
)

/** Friend graph: who the user knows and the friendship state. Devices live in [DeviceStore]. */
class FriendStore internal constructor(private val db: ObscuraDatabase) {
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    suspend fun add(userId: String, username: String, status: FriendStatus) = withContext(dispatcher) {
        val now = System.currentTimeMillis()
        db.friendQueries.insert(userId, username, status.value, now, now)
    }

    /** The stored record for [userId], or null if this user is not in the friend graph at all. */
    suspend fun get(userId: String): FriendData? = withContext(dispatcher) {
        db.friendQueries.selectById(userId).executeAsOneOrNull()?.toFriendData()
    }

    /**
     * Move an EXISTING record to [status], keeping its username.
     *
     * Deliberately separate from [add]: `add` writes a caller-supplied username, and the only
     * usernames a peer can supply are attacker-chosen. A status change
     * driven by an inbound message must never be able to carry a name with it.
     */
    suspend fun updateStatus(userId: String, status: FriendStatus) = withContext(dispatcher) {
        db.friendQueries.updateStatus(status.value, System.currentTimeMillis(), userId)
    }

    suspend fun getAccepted(): List<FriendData> = withContext(dispatcher) {
        db.friendQueries.selectByStatus(FriendStatus.ACCEPTED.value).executeAsList().map { it.toFriendData() }
    }

    suspend fun getAll(): List<FriendData> = withContext(dispatcher) {
        db.friendQueries.selectAll().executeAsList().map { it.toFriendData() }
    }

    suspend fun remove(userId: String) = withContext(dispatcher) {
        db.friendQueries.deleteById(userId)
    }

    suspend fun clear() = withContext(dispatcher) {
        db.friendQueries.deleteAll()
    }
}
