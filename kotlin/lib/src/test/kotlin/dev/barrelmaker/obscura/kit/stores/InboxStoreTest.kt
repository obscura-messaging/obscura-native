package dev.barrelmaker.obscura.kit.stores

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.barrelmaker.obscura.kit.db.ObscuraDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The durable inbox. Once the kit has acked, the row is the only copy, so getting these rules wrong
 * loses messages permanently.
 */
class InboxStoreTest {

    private lateinit var db: ObscuraDatabase
    private lateinit var inbox: InboxStore

    @BeforeEach
    fun setup() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ObscuraDatabase.Schema.create(driver)
        db = ObscuraDatabase(driver)
        inbox = InboxStore(db)
    }

    private fun record(
        envelopeId: String,
        kind: String = "APP_ENTRY",
        payload: ByteArray = "{}".toByteArray(),
        modelKey: String? = "directMessage",
    ) = InboxInsert(
        envelopeId = envelopeId,
        kind = kind,
        senderUserId = "user_peer",
        senderDeviceId = "device_peer",
        modelKey = modelKey,
        entryId = "entry_1",
        sentAt = 1_700_000_000_000,
        payload = payload,
    )

    // ── Idempotence ───────────────────────────────────────────────────────────

    /**
     * The rule the whole design leans on. Persist-then-ack **guarantees** redelivery: the ack is
     * best-effort and its failure is swallowed, so the server's per-connection cursor re-sends on
     * the next connection. That is correct behaviour — losing the message would be worse.
     * The envelope id is therefore the inbox deduplication key.
     */
    @Test
    fun `a redelivered envelope does not create a second row`() = runBlocking {
        assertTrue(inbox.put(record("env_1")), "first insert should create a row")
        assertFalse(inbox.put(record("env_1")), "redelivery must be absorbed, not duplicated")

        assertEquals(1L, inbox.depth())
    }

    /**
     * `put` returning false is a *successful* absorption, not a failure — the message is already
     * durably held. The caller must still ack, because acking is what stops the server sending it a
     * third time. This pins the contract that makes that safe to rely on.
     */
    @Test
    fun `redelivery keeps the originally stored row rather than replacing it`() = runBlocking {
        inbox.put(record("env_1", payload = "original".toByteArray()))
        inbox.put(record("env_1", payload = "tampered".toByteArray()))

        val rows = inbox.peek()
        assertEquals(1, rows.size)
        assertEquals("original", String(rows[0].payload),
            "INSERT OR IGNORE keeps the first write; a later copy of the same envelope cannot overwrite it")
    }

    /**
     * **`put` must never report "stored" for a message it did not store.** This is the precondition
     * the entire feature rests on: the caller acks on a successful `put`, and an ack is a DELETE, so
     * a `put` that lies destroys the message.
     *
     * The subtle version of that lie is why this test exists. `INSERT OR IGNORE` suppresses EVERY
     * constraint, not just the `envelope_id UNIQUE` it is written for — so the obvious "did a row
     * get added?" implementations (`changes()`, or comparing `depth()` before and after) report a
     * NOT NULL or CHECK violation exactly as they report a harmless redelivery. The caller acks on a
     * redelivery. `put` therefore asserts the postcondition directly, and throws when it does not
     * hold.
     *
     * Dropping the table is a blunt way to make the write fail, and that is the point: whatever the
     * reason, "the row is not there" must reach the caller as an exception and never as `false`.
     */
    @Test
    fun `put throws rather than reporting stored when the row is not there`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ObscuraDatabase.Schema.create(driver)
        val db = ObscuraDatabase(driver)
        val store = InboxStore(db)
        driver.execute(null, "DROP TABLE InboxRow", 0)

        assertThrows(Exception::class.java) {
            runBlocking { store.put(record("env_1")) }
        }
        // `assertThrows` RETURNS the Throwable, which would make this function non-Unit — and JUnit 5
        // silently ignores a non-void @Test. It is in `CLAUDE.md` as a known trap, and it caught this
        // very test: it sat in the file, compiled, and never ran, while the suite stayed green.
        // Verified by the class count (14, not 13), not by the tick.
        Unit
    }

    // ── Peek is side-effect free ──────────────────────────────────────────────

    /**
     * The crash-safety property, stated as a test because it reads like a bug otherwise: draining
     * twice without consuming returns the same rows. An app that dies between peek and consume
     * reprocesses them, and the merge rules downstream are idempotent so that converges.
     */
    @Test
    fun `peeking twice without consuming returns the same rows`() = runBlocking {
        inbox.put(record("env_1"))
        inbox.put(record("env_2"))

        val first = inbox.peek()
        val second = inbox.peek()

        assertEquals(2, first.size)
        assertEquals(first, second)
        assertEquals(2L, inbox.depth(), "peek must not consume")
    }

    /** Drain order is oldest-first, because the app processes in arrival order. */
    @Test
    fun `peek returns rows in insertion order and respects the limit`() = runBlocking {
        repeat(5) { inbox.put(record("env_$it", payload = "env_$it".toByteArray())) }

        val rows = inbox.peek(limit = 3)

        assertEquals(listOf("env_0", "env_1", "env_2"), rows.map { String(it.payload) })
    }

    /**
     * `id` must be monotonic across the whole install, and that is why the column is AUTOINCREMENT.
     * A plain INTEGER PRIMARY KEY aliases rowid, which SQLite **reuses after deletion** — so a
     * drained-then-refilled inbox would hand out ids that go backwards, and `peek` orders by id.
     */
    @Test
    fun `ids keep increasing after rows are consumed`() = runBlocking {
        inbox.put(record("env_1"))
        inbox.put(record("env_2"))
        val firstBatch = inbox.peek()
        inbox.consume(firstBatch.map { it.id })

        inbox.put(record("env_3"))
        val afterDrain = inbox.peek().single()

        assertTrue(afterDrain.id > firstBatch.maxOf { it.id },
            "rowid reuse would make drain order go backwards; AUTOINCREMENT is what prevents it")
    }

    // ── Removal ───────────────────────────────────────────────────────────────

    @Test
    fun `consume is idempotent and accepts a subset`() = runBlocking {
        repeat(3) { inbox.put(record("env_$it", payload = "env_$it".toByteArray())) }
        val rows = inbox.peek()

        inbox.consume(listOf(rows[0].id))
        inbox.consume(listOf(rows[0].id)) // again — partial progress is normal, not an error

        assertEquals(2L, inbox.depth())
        assertEquals(listOf("env_1", "env_2"), inbox.peek().map { String(it.payload) })
    }

    @Test
    fun `consume of an empty list is a no-op`() = runBlocking {
        inbox.put(record("env_1"))

        inbox.consume(emptyList())

        assertEquals(1L, inbox.depth())
    }

    /**
     * A discard is data loss the app chose — the server's copy is already gone, so nothing else
     * holds these bytes. It must be logged as a security-relevant event; this pins that the hook
     * fires.
     */
    @Test
    fun `discard removes rows and reports them for the security log`() = runBlocking {
        inbox.put(record("env_1"))
        inbox.put(record("env_2"))
        val logged = mutableListOf<Pair<List<Long>, String>>()
        inbox.onDiscard = { ids, reason -> logged.add(ids to reason) }

        val rows = inbox.peek()
        inbox.discard(listOf(rows[0].id), reason = "unknown modelKey from a newer peer")

        assertEquals(1L, inbox.depth())
        assertEquals(1, logged.size)
        assertEquals("unknown modelKey from a newer peer", logged[0].second)
    }

    @Test
    fun `discarding nothing does not log a security event`() = runBlocking {
        var calls = 0
        inbox.onDiscard = { _, _ -> calls++ }

        inbox.discard(emptyList(), reason = "nothing to do")

        assertEquals(0, calls, "an empty discard is not a data-loss event and must not read as one")
    }

    // ── Depth ─────────────────────────────────────────────────────────────────

    @Test
    fun `depth reflects what is waiting`() = runBlocking {
        assertEquals(0L, inbox.depth())
        repeat(4) { inbox.put(record("env_$it")) }
        assertEquals(4L, inbox.depth())

        inbox.consume(inbox.peek(limit = 2).map { it.id })

        assertEquals(2L, inbox.depth())
    }

    // ── The record ────────────────────────────────────────────────────────────

    @Test
    fun `every field survives a round trip, including opaque payload bytes`() = runBlocking {
        val payload = byteArrayOf(0, 1, 2, -1, 127, -128) // deliberately not valid UTF-8
        inbox.put(record("env_1", payload = payload))

        val row = inbox.peek().single()

        assertEquals("APP_ENTRY", row.kind)
        assertEquals("user_peer", row.senderUserId)
        assertEquals("device_peer", row.senderDeviceId)
        assertEquals("directMessage", row.modelKey)
        assertEquals("entry_1", row.entryId)
        assertArrayEquals(payload, row.payload, "payload is opaque bytes and must not be re-encoded")
    }

    /**
     * An unknown arm has no AppEntry to derive from, so those columns are null. The row
     * still exists, which is the point — the message is preserved rather than destroyed.
     */
    @Test
    fun `an unknown arm is stored with null AppEntry fields`() = runBlocking {
        inbox.put(
            record("env_1", kind = "UNKNOWN_ARM", modelKey = null)
                .copy(entryId = null, sentAt = null)
        )

        val row = inbox.peek().single()

        assertEquals("UNKNOWN_ARM", row.kind)
        assertNull(row.modelKey)
        assertNull(row.entryId)
        assertNull(row.sentAt)
    }

    // ── Device-wipe carve-out ─────────────────────────────────────────────────

    /**
     * A device wipe must be able to destroy decrypted plaintext. It takes no selector so it cannot
     * become an eviction policy.
     */
    @Test
    fun `wipe destroys everything`() = runBlocking {
        repeat(3) { inbox.put(record("env_$it")) }

        inbox.wipe()

        assertEquals(0L, inbox.depth())
    }
}
