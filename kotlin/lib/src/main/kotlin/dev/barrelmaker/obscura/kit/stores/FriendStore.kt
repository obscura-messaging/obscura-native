package dev.barrelmaker.obscura.kit.stores

import dev.barrelmaker.obscura.kit.db.ObscuraDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class FriendStatus(val value: String) {
    PENDING_SENT("pending_sent"),
    PENDING_RECEIVED("pending_received"),
    ACCEPTED("accepted")
}

data class FriendData(
    val userId: String,
    val username: String,
    val status: FriendStatus,
    val devices: List<FriendDeviceInfo> = emptyList(),
)

data class FriendDeviceInfo(
    val id: String,
    val name: String,
)

/**
 * The stored `devices` JSON column as [FriendDeviceInfo]s. Unparsable JSON yields an empty list:
 * a friend whose device blob is corrupt is still a friend, and failing the whole read would take
 * the friend graph down with it.
 */
internal fun parseFriendDevices(json: String): List<FriendDeviceInfo> {
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            FriendDeviceInfo(
                id = obj.optString("deviceId", ""),
                name = obj.optString("deviceName", ""),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * FriendStore - Confined coroutines. Manages friend state + device lists.
 */
class FriendStore internal constructor(private val db: ObscuraDatabase) {
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    suspend fun add(userId: String, username: String, status: FriendStatus, devices: List<FriendDeviceInfo> = emptyList()) =
        withContext(dispatcher) {
            val devicesJson = JSONArray(devices.map { d ->
                JSONObject().apply {
                    put("deviceId", d.id)
                    put("deviceName", d.name)
                }
            }).toString()

            val now = System.currentTimeMillis()
            db.friendQueries.insert(userId, username, status.value, devicesJson, now, now)
        }

    /** The stored record for [userId], or null if this user is not in the friend graph at all. */
    suspend fun get(userId: String): FriendData? = withContext(dispatcher) {
        db.friendQueries.selectById(userId).executeAsOneOrNull()?.toFriendData()
    }

    /**
     * Move an EXISTING record to [status], keeping its username and devices.
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

    suspend fun updateDevices(
        userId: String,
        devices: List<FriendDeviceInfo>,
        timestamp: Long = System.currentTimeMillis(),
    ) = withContext(dispatcher) {
        // Still gated on the row existing: an UPDATE against an absent user_id is a silent no-op,
        // and that is the intended behaviour (a stranger's DEVICE_ANNOUNCE must not create a friend).
        // The explicit read keeps that fact readable instead of implied by SQL semantics.
        db.friendQueries.selectById(userId).executeAsOneOrNull() ?: return@withContext
        val devicesJson = JSONArray(devices.map { d ->
            JSONObject().apply {
                put("deviceId", d.id)
                put("deviceName", d.name)
            }
        }).toString()
        db.friendQueries.updateDevices(devicesJson, timestamp, timestamp, userId, timestamp)
    }

    suspend fun remove(userId: String) = withContext(dispatcher) {
        db.friendQueries.deleteById(userId)
    }

    suspend fun exportAll(): String = withContext(dispatcher) {
        val friends = db.friendQueries.selectAll().executeAsList()
        val arr = JSONArray(friends.map { f ->
            JSONObject().apply {
                put("userId", f.user_id)
                put("username", f.username)
                put("status", f.status)
                put("devices", JSONArray(f.devices))
            }
        })
        arr.toString()
    }

    suspend fun importAll(data: String) = withContext(dispatcher) {
        val arr = JSONArray(data)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val now = System.currentTimeMillis()
            db.friendQueries.insert(
                obj.getString("userId"),
                obj.getString("username"),
                obj.getString("status"),
                obj.optString("devices", "[]"),
                now, now
            )
        }
    }

    private fun dev.barrelmaker.obscura.kit.Friend.toFriendData(): FriendData {
        return FriendData(
            userId = user_id,
            username = username,
            status = FriendStatus.entries.find { it.value == status } ?: FriendStatus.PENDING_SENT,
            devices = parseFriendDevices(devices),
        )
    }
}
