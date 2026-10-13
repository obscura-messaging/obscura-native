package dev.barrelmaker.obscura.kit

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.protobuf.ByteString
import dev.barrelmaker.obscura.kit.crypto.SignalStore
import dev.barrelmaker.obscura.kit.crypto.UuidCodec
import dev.barrelmaker.obscura.kit.db.ObscuraDatabase
import dev.barrelmaker.obscura.kit.managers.SignalKeyUtils
import dev.barrelmaker.obscura.kit.messaging.Messenger
import dev.barrelmaker.obscura.kit.messaging.parsePeerBundles
import dev.barrelmaker.obscura.kit.network.APIClient
import kotlinx.coroutines.runBlocking
import obscura.client.v1.Client.ClientMessage
import org.json.JSONArray
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import xyz.obscura.server.contracts.ObscuraProtocol.Envelope
import java.util.UUID

class ReceiveDurabilityTest {
    private val peerUser = UUID.randomUUID().toString()
    private val peerDevice = UUID.randomUUID().toString()

    private suspend fun fixture(malformedPlaintext: Boolean = false): Triple<ObscuraClient, JdbcSqliteDriver, Envelope> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ObscuraDatabase.Schema.create(driver)
        val c = ObscuraClient(ObscuraConfig("http://127.0.0.1:1", "test"), driver, storedSession())
        val keys = SignalKeyUtils.generateDeviceKeys(c.signalStore)
        c.devices.storeIdentity(SELF_DEVICE_ID)
        assertTrue(c.auth.restore())
        val sender = SignalStore(newInMemoryDatabase()).also { it.generateIdentity() }
        val messenger = Messenger(sender, APIClient("http://127.0.0.1:1") { null })
        val bundle = keys.toJson().put("deviceId", SELF_DEVICE_ID)
            .put("oneTimePreKey", keys.oneTimePreKeys.first().toJson())
        messenger.establishSession(parsePeerBundles(JSONArray().put(bundle)).single())
        val message = ClientMessage.newBuilder().setAppEntry(obscura.client.v1.appEntry {
            model = "opaque"
            id = "entry"
            timestamp = 1
            data = ByteString.copyFromUtf8("""{"deviceId":"payload-is-not-discovery"}""")
        }).build()
        val encrypted = if (malformedPlaintext) {
            val ciphertext = org.signal.libsignal.protocol.SessionCipher(sender, Messenger.addressFor(SELF_DEVICE_ID))
                .encrypt(byteArrayOf(-1, -1, -1))
            obscura.client.v1.Client.EncryptedMessage.newBuilder()
                .setType(obscura.client.v1.Client.EncryptedMessage.Type.TYPE_PREKEY_MESSAGE)
                .setContent(ByteString.copyFrom(ciphertext.serialize())).build().toByteString()
        } else messenger.prepareSubmission(SELF_DEVICE_ID, message).message
        val envelope = Envelope.newBuilder()
            .setId(UuidCodec.uuidToBytes(UUID.randomUUID()))
            .setSenderId(UuidCodec.uuidToBytes(UUID.fromString(peerUser)))
            .setSenderDeviceId(UuidCodec.uuidToBytes(UUID.fromString(peerDevice)))
            .setMessage(encrypted).build()
        return Triple(c, driver, envelope)
    }

    @Test fun `successful decrypt adds stamped sender additively before persist then ack`() = runBlocking {
        val (c, driver, envelope) = fixture()
        val old = UUID.randomUUID().toString()
        c.devices.add(peerUser, old)
        var acks = 0
        c.receive(envelope) {
            assertEquals(1L, c.inbox.depth())
            assertEquals(setOf(old, peerDevice), c.devices.devicesOf(peerUser).toSet())
            acks++
        }
        assertEquals(1, acks)
        assertEquals(peerDevice, c.inbox.peek().single().senderDeviceId)
        c.auth.end()
        driver.close()
    }

    @Test fun `a persisted ciphertext redelivery acks without decrypting twice or waking twice`() = runBlocking {
        val (c, driver, envelope) = fixture()
        var acks = 0
        c.receive(envelope) { acks++ }
        c.receive(envelope) { acks++ }
        assertEquals(2, acks)
        assertEquals(1L, c.inbox.depth())
        assertEquals("opaque", c.messageReceived.tryReceive().getOrNull())
        assertNull(c.messageReceived.tryReceive().getOrNull())
        c.auth.end()
        driver.close()
    }

    @Test fun `failed decrypt and malformed sender never add directory evidence or ack`() = runBlocking {
        val (c, driver, envelope) = fixture()
        var acks = 0
        c.receive(envelope.toBuilder().setMessage(ByteString.copyFromUtf8("corrupt")).build()) { acks++ }
        c.receive(envelope.toBuilder().clearSenderDeviceId().build()) { acks++ }
        assertEquals(0, acks)
        assertTrue(c.devices.devicesOf(peerUser).isEmpty())
        assertEquals(0L, c.inbox.depth())
        c.auth.end()
        driver.close()
    }

    @Test fun `durable directory and inbox failures both withhold ack`() = runBlocking {
        for (table in listOf("Device", "InboxRow")) {
            val (c, driver, envelope) = fixture()
            driver.execute(null, "CREATE TRIGGER fail_write BEFORE INSERT ON $table BEGIN SELECT RAISE(FAIL, 'disk unavailable'); END", 0)
            var acks = 0
            c.receive(envelope) { acks++ }
            assertEquals(0, acks, table)
            assertEquals(0L, c.inbox.depth())
            assertTrue(c.getDebugLog().any { it.contains("not stored") })
            c.auth.end()
            driver.close()
        }
    }

    @Test fun `authenticated malformed plaintext teaches stamped sender but is never acked`() = runBlocking {
        val (c, driver, envelope) = fixture(malformedPlaintext = true)
        var acks = 0
        c.receive(envelope) { acks++ }
        assertEquals(listOf(peerDevice), c.devices.devicesOf(peerUser))
        assertEquals(0, acks)
        assertEquals(0L, c.inbox.depth())
        assertTrue(c.getDebugLog().any { it.contains("invalid client message") })
        c.auth.end()
        driver.close()
    }
}
