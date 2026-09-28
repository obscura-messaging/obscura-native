package dev.barrelmaker.obscura.kit

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.barrelmaker.obscura.kit.db.ObscuraDatabase
import dev.barrelmaker.obscura.kit.stores.pragma
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * `purgeAttachment` removes this device's decrypted copy of one attachment, unrecoverably. The
 * cache holds plaintext media, so "unrecoverable" is checked on disk, WAL included.
 */
class AttachmentPurgeTest {

    @Test
    fun `purge removes only the named attachment and leaves no plaintext on disk`(@TempDir dir: File) = runBlocking {
        val path = File(dir, "purge.db")
        val driver = JdbcSqliteDriver("jdbc:sqlite:${path.absolutePath}")
        ObscuraDatabase.Schema.create(driver)
        driver.pragma("PRAGMA journal_mode = WAL")
        val client = ObscuraClient(ObscuraConfig(apiUrl = "https://obscura.invalid"), driver)
        val secret = "PLAINTEXT-PIX-5e2b"
        client.db.attachmentCacheQueries.insert("att_1", secret.toByteArray(), secret.length.toLong(), 1)
        client.db.attachmentCacheQueries.insert("att_2", "keep".toByteArray(), 4, 2)
        fun onDisk(): Boolean = listOf(path, File("${path.absolutePath}-wal"))
            .filter { it.exists() }
            .any { String(it.readBytes(), Charsets.ISO_8859_1).contains(secret) }
        assertTrue(onDisk(), "control: the plaintext must be on disk before purge")

        client.purgeAttachment("att_1")

        assertNull(client.db.attachmentCacheQueries.selectById("att_1").executeAsOneOrNull())
        assertNotNull(client.db.attachmentCacheQueries.selectById("att_2").executeAsOneOrNull())
        assertFalse(onDisk(), "purged plaintext must not survive in the database or its WAL")
        driver.close()
    }

    @Test
    fun `purging an attachment that is not cached is a no-op`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ObscuraDatabase.Schema.create(driver)
        val client = ObscuraClient(ObscuraConfig(apiUrl = "https://obscura.invalid"), driver)

        client.purgeAttachment("never_cached")
    }
}
