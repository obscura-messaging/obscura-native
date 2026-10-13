package dev.barrelmaker.obscura.kit

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.barrelmaker.obscura.kit.db.ObscuraDatabase
import dev.barrelmaker.obscura.kit.persistence.SessionStorage

fun newInMemoryDatabase(): ObscuraDatabase {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    ObscuraDatabase.Schema.create(driver)
    return ObscuraDatabase(driver)
}

class MemoryStorage(var data: Map<String, String>? = null) : SessionStorage {
    override fun save(data: Map<String, String>) { this.data = data }
    override fun load() = data
    override fun clear() { data = null }
}

fun newClient(apiUrl: String = "https://obscura.invalid", storage: SessionStorage = MemoryStorage()) =
    ObscuraClient(ObscuraConfig(apiUrl, deviceName = "Test"), driver = null, sessionStorage = storage)

fun jwt(sub: String, exp: Long): String {
    val encode = { s: String -> java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray()) }
    return "${encode("""{"alg":"none"}""")}.${encode("""{"sub":"$sub","exp":$exp}""")}.sig"
}

const val SELF_USER_ID = "019f025a-0000-7de6-84b6-000000000001"
const val SELF_DEVICE_ID = "019f025a-0000-7de6-84b6-0000000000d1"
const val FAR_FUTURE = 4_102_444_800L

fun storedSession(token: String = jwt(SELF_USER_ID, FAR_FUTURE), refreshToken: String = "rt") = MemoryStorage(mapOf(
    "token" to token, "refreshToken" to refreshToken, "userId" to SELF_USER_ID,
    "deviceId" to SELF_DEVICE_ID, "username" to "self",
))

/** Authenticated through the stored-session path, with no server to connect to. */
suspend fun authenticatedClient(storage: MemoryStorage = storedSession()): ObscuraClient {
    val client = newClient(apiUrl = "http://127.0.0.1:1", storage = storage)
    check(client.restorePersistedSession())
    return client
}
