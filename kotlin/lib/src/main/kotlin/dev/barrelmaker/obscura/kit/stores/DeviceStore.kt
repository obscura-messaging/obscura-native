package dev.barrelmaker.obscura.kit.stores

import dev.barrelmaker.obscura.kit.db.ObscuraDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** This device's id, and the device ids of every user the client addresses, its own included. */
internal class DeviceStore(private val db: ObscuraDatabase) {
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val queries = db.deviceQueries

    suspend fun storeIdentity(deviceId: String) = withContext(dispatcher) { queries.insertIdentity(deviceId) }

    suspend fun getIdentity(): String? = withContext(dispatcher) { queries.selectIdentity().executeAsOneOrNull() }

    suspend fun add(userId: String, deviceId: String) = withContext(dispatcher) {
        addInTransaction(userId, deviceId)
    }

    internal fun addInTransaction(userId: String, deviceId: String) = queries.insert(userId, deviceId)

    suspend fun devicesOf(userId: String): List<String> = withContext(dispatcher) {
        queries.selectByUser(userId).executeAsList()
    }

    suspend fun replace(userId: String, deviceIds: List<String>) = withContext(dispatcher) {
        db.transaction {
            queries.deleteByUser(userId)
            deviceIds.forEach { queries.insert(userId, it) }
        }
    }
}
