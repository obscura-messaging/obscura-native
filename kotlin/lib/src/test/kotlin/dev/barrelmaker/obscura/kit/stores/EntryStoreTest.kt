package dev.barrelmaker.obscura.kit.stores

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.barrelmaker.obscura.kit.db.ObscuraDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Raw entry storage (`KIT_API.md` §8.1).
 *
 * The property under test throughout is **that the kit does not interpret anything**. Merge moved to
 * the app; this store writes what it is given and returns it unchanged. Most of these tests exist to
 * pin the *absence* of behaviour, which is unusual and deliberate: every one of them would still
 * pass if someone re-added merge logic here, EXCEPT the ones that assert a blind overwrite — so
 * those are the ones that matter.
 */
class EntryStoreTest {

    private lateinit var store: EntryStore

    @BeforeEach
    fun setup() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ObscuraDatabase.Schema.create(driver)
        store = EntryStore(ObscuraDatabase(driver), driver)
    }

    private fun entry(
        id: String,
        data: String = """{"content":"hi"}""",
        sentAt: Long = 1_000,
        device: String = "device_a",
        localMetadata: String? = null,
    ) = StoredEntry(
        id = id,
        data = data,
        sentAt = sentAt,
        authorDeviceId = device,
        localMetadata = localMetadata,
    )

    @Test
    fun `put then all returns what was written`() = runBlocking {
        store.put("directMessage", entry("dm_1"))

        val all = store.all("directMessage")

        assertEquals(1, all.size)
        assertEquals("dm_1", all[0].id)
        assertEquals("""{"content":"hi"}""", all[0].data)
        assertEquals(1_000L, all[0].sentAt)
        assertEquals("device_a", all[0].authorDeviceId)
        assertNull(all[0].localMetadata)
    }

    /**
     * `put` is a blind upsert: an older write replaces a newer one,
     * because by the time a write reaches this class the app has already decided who wins.
     *
     * Merge policy must not be duplicated here or overrule the app's decision.
     */
    @Test
    fun `put is blind — an older write overwrites a newer one`() = runBlocking {
        store.put("pix", entry("pix_1", data = """{"v":"new"}""", sentAt = 9_000))
        store.put("pix", entry("pix_1", data = """{"v":"old"}""", sentAt = 1_000))

        val all = store.all("pix")

        assertEquals(1, all.size, "same (model, id) is one row")
        assertEquals("""{"v":"old"}""", all[0].data,
            "the store must not re-decide the merge; the app already did")
        assertEquals(1_000L, all[0].sentAt)
    }

    @Test
    fun `models do not bleed into each other`() = runBlocking {
        store.put("directMessage", entry("a"))
        store.put("story", entry("b"))

        assertEquals(listOf("a"), store.all("directMessage").map { it.id })
        assertEquals(listOf("b"), store.all("story").map { it.id })
        assertEquals(emptyList<String>(), store.all("profile").map { it.id })
    }

    /**
     * `data` is opaque. The kit stores the string it is handed and returns it byte-for-byte — it does
     * not parse, re-serialize, validate or normalise. Re-serializing would reorder keys and change
     * the bytes the app hashed or compared.
     */
    @Test
    fun `data is stored verbatim, including content the kit cannot parse`() = runBlocking {
        val notJson = """this is not json at all {{{"""
        store.put("weird", entry("w", data = notJson))

        assertEquals(notJson, store.all("weird").single().data,
            "the kit must not validate a shape it is forbidden to read (SPEC §0.4)")
    }

    @Test
    fun `unicode and nested payloads survive unchanged`() = runBlocking {
        val payload = """{"content":"sunset 🌅","meta":{"x":0.5,"tags":["a","b"]}}"""
        store.put("story", entry("s", data = payload))

        assertEquals(payload, store.all("story").single().data)
    }

    @Test
    fun `all on an unknown model is empty rather than an error`() = runBlocking {
        assertEquals(emptyList<StoredEntry>(), store.all("neverSeen"))
    }

    /**
     * The merge metadata has to survive the round trip, because it IS the app's merge input: REPLACE
     * is a total order on `(sentAt, authorDeviceId)` (§8.2). A store that dropped or rewrote either
     * would make the app's tie-break silently non-deterministic across devices.
     */
    @Test
    fun `merge metadata round-trips exactly`() = runBlocking {
        store.put("pix", entry("p", sentAt = 1_700_000_000_123, device = "device_zzz"))

        val stored = store.all("pix").single()

        assertEquals(1_700_000_000_123L, stored.sentAt)
        assertEquals("device_zzz", stored.authorDeviceId)
    }

    @Test
    fun `local metadata round-trips verbatim and remains nullable`() = runBlocking {
        val metadata = """{"uploadState":"complete","attempts":2}"""
        store.put("pix", entry("with_meta", localMetadata = metadata))
        store.put("pix", entry("without_meta"))

        val stored = store.all("pix").associateBy { it.id }

        assertEquals(metadata, stored.getValue("with_meta").localMetadata)
        assertNull(stored.getValue("without_meta").localMetadata)
    }

    @Test
    fun `put replaces local metadata with the app supplied value`() = runBlocking {
        store.put("pix", entry("pix_1", localMetadata = """{"state":"pending"}"""))
        store.put("pix", entry("pix_1", localMetadata = null))

        assertNull(store.all("pix").single().localMetadata)
    }

    @Test
    fun `erase removes only the named entry`() = runBlocking {
        store.put("directMessage", entry("dm_1"))
        store.put("directMessage", entry("dm_2"))
        store.put("pix", entry("dm_1"))

        store.erase("directMessage", "dm_1")

        assertEquals(listOf("dm_2"), store.all("directMessage").map { it.id })
        assertEquals(listOf("dm_1"), store.all("pix").map { it.id })
    }

    @Test
    fun `erasing a missing entry is a no-op`() = runBlocking {
        store.put("directMessage", entry("dm_1"))

        store.erase("directMessage", "never_written")

        assertEquals(1, store.all("directMessage").size)
    }

    /**
     * The guarantee `erase` exists for: the erased content is not recoverable from the database
     * files. WAL mode is the hard case, because a deleted page otherwise lingers in `-wal`.
     */
    @Test
    fun `erased content is not recoverable from the database or its WAL`(@TempDir dir: File) = runBlocking {
        val path = File(dir, "erase.db")
        val driver = JdbcSqliteDriver("jdbc:sqlite:${path.absolutePath}")
        ObscuraDatabase.Schema.create(driver)
        driver.pragma("PRAGMA journal_mode = WAL")
        val fileStore = EntryStore(ObscuraDatabase(driver), driver)
        val secret = "ERASE-ME-7f3a9c"
        fun onDisk(): Boolean = listOf(path, File("${path.absolutePath}-wal"))
            .filter { it.exists() }
            .any { String(it.readBytes(), Charsets.ISO_8859_1).contains(secret) }

        fileStore.put("directMessage", entry("dm_1", data = """{"content":"$secret"}"""))
        assertTrue(onDisk(), "control: the secret must be on disk before erase")

        fileStore.erase("directMessage", "dm_1")

        assertFalse(onDisk(), "erased content must not survive in the database or its WAL")
        driver.close()
    }
}
