package dev.barrelmaker.obscura.kit.stores

import dev.barrelmaker.obscura.kit.db.ObscuraDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DeviceIdentityData(
    val deviceId: String,
)

/** One addressable device of [userId]. [name] is a display label and may be empty. */
data class DeviceData(
    val userId: String,
    val deviceId: String,
    val name: String = "",
)

/**
 * The device directory: this device's identity plus every device the client can address, its own
 * included. The only store of device lists; nothing else caches them.
 */
class DeviceStore internal constructor(private val db: ObscuraDatabase) {
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    suspend fun storeIdentity(identity: DeviceIdentityData) = withContext(dispatcher) {
        db.deviceQueries.insertIdentity(identity.deviceId)
    }

    suspend fun getIdentity(): DeviceIdentityData? = withContext(dispatcher) {
        db.deviceQueries.selectIdentity().executeAsOneOrNull()?.let { DeviceIdentityData(it.device_id) }
    }

    suspend fun devicesOf(userId: String): List<DeviceData> = withContext(dispatcher) {
        db.deviceQueries.selectByUser(userId).executeAsList().map { DeviceData(it.user_id, it.device_id, it.name) }
    }

    suspend fun add(device: DeviceData) = withContext(dispatcher) {
        db.deviceQueries.insert(device.userId, device.deviceId, device.name)
    }

    /**
     * Replace [userId]'s devices with the server's current list. Known names are kept. A friend's
     * list is stamped now, so an announcement older than this fetch cannot revert it.
     */
    suspend fun replaceFromServer(userId: String, deviceIds: List<String>) = withContext(dispatcher) {
        db.transaction {
            val names = db.deviceQueries.selectByUser(userId).executeAsList().associate { it.device_id to it.name }
            writeDevices(userId, deviceIds.map { DeviceData(userId, it, names[it].orEmpty()) })
            val now = System.currentTimeMillis()
            db.friendQueries.claimDevicesUpdate(now, now, userId, now)
        }
    }

    /**
     * Apply a friend's DEVICE_ANNOUNCE. Only an existing friend's list changes, and only when
     * [timestamp] is newer than its last device-list write. Returns whether it applied.
     */
    suspend fun applyAnnounce(userId: String, devices: List<DeviceData>, timestamp: Long): Boolean =
        withContext(dispatcher) {
            db.transactionWithResult {
                db.friendQueries.claimDevicesUpdate(timestamp, timestamp, userId, timestamp)
                val claimed = db.friendQueries.changes().executeAsOne() == 1L
                if (claimed) writeDevices(userId, devices)
                claimed
            }
        }

    /** Replace [userId]'s devices with [devices] as given (link approval, friend import). */
    suspend fun replace(userId: String, devices: List<DeviceData>) = withContext(dispatcher) {
        db.transaction { writeDevices(userId, devices) }
    }

    suspend fun clear() = withContext(dispatcher) {
        db.deviceQueries.deleteAll()
        db.deviceQueries.deleteIdentity()
    }

    private fun writeDevices(userId: String, devices: List<DeviceData>) {
        require(devices.all { it.userId == userId }) { "Device list for $userId names another user" }
        db.deviceQueries.deleteByUser(userId)
        devices.distinctBy { it.deviceId }.forEach { db.deviceQueries.insert(userId, it.deviceId, it.name) }
    }
}
