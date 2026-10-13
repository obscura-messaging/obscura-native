package dev.barrelmaker.obscura.kit.managers

import dev.barrelmaker.obscura.kit.SubmissionResult
import dev.barrelmaker.obscura.kit.SubmissionStatus.ACCEPTED
import dev.barrelmaker.obscura.kit.SubmissionStatus.REJECTED
import dev.barrelmaker.obscura.kit.stores.DeviceData
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import obscura.client.v1.Client.ClientMessage
import obscura.client.v1.Client.EncryptedMessage.Type.TYPE_ENCRYPTED_MESSAGE
import obscura.client.v1.Client.EncryptedMessage.Type.TYPE_PREKEY_MESSAGE
import obscura.client.v1.appEntry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The send path against real Signal peers on an in-process server. Every bundle fetch consumes a
 * one-time prekey on each of the user's devices, so the fetch counts here are the prekey cost.
 */
class MessageSenderTest {
    private val server = FakeObscuraServer()
    private val alice = server.register(user())
    private val bob = server.register(user())

    @AfterEach fun close() = server.close()

    private fun user() = UUID.randomUUID().toString()

    private fun entry(id: String): ClientMessage = ClientMessage.newBuilder()
        .setAppEntry(appEntry { model = "m"; this.id = id }).build()

    private suspend fun FakeDevice.send(to: FakeDevice, id: String, accepted: Set<String> = emptySet()) =
        sender.send(to.userId, entry(id), accepted)

    private fun accepted(device: FakeDevice) = SubmissionResult(device.userId, device.deviceId, ACCEPTED)

    @Test
    fun `repeated sends fetch bundles once and reuse the session`() = runBlocking {
        repeat(3) { assertEquals(listOf(accepted(bob)), alice.send(bob, "e$it")) }

        val received = bob.receive()
        assertEquals(listOf("e0", "e1", "e2"), received.map { it.message.appEntry.id })
        // Unacknowledged sessions keep sending prekey messages, all from the one session.
        assertEquals(1, received.map { it.baseKey }.distinct().size)
        assertEquals(1, server.bundleFetches(bob.userId))
        assertEquals(99, bob.remainingPreKeys)
    }

    @Test
    fun `a refresh never rebuilds an established session`() = runBlocking {
        alice.send(bob, "hello")
        bob.receive()

        // Bob has Alice's session from her prekey message but has never listed her devices.
        assertEquals(listOf(accepted(alice)), bob.send(alice, "reply"))
        assertEquals(1, server.bundleFetches(alice.userId))
        assertEquals(listOf(TYPE_ENCRYPTED_MESSAGE), alice.receive().map { it.type })

        alice.send(bob, "after-reply")
        assertEquals(listOf(TYPE_ENCRYPTED_MESSAGE), bob.receive().map { it.type })
        assertEquals(1, server.bundleFetches(bob.userId))
    }

    @Test
    fun `a newly listed device without a session costs one fetch and keeps existing sessions`() = runBlocking {
        alice.send(bob, "first")
        val firstKey = bob.receive().single().baseKey
        val bobTablet = server.register(bob.userId)
        alice.directory.replace(bob.userId, listOf(DeviceData(bob.userId, bob.deviceId), DeviceData(bob.userId, bobTablet.deviceId)))

        assertEquals(setOf(accepted(bob), accepted(bobTablet)), alice.send(bob, "second").toSet())

        assertEquals(firstKey, bob.receive().single().baseKey)
        assertEquals(TYPE_PREKEY_MESSAGE, bobTablet.receive().single().type)
        assertEquals(2, server.bundleFetches(bob.userId))
    }

    @Test
    fun `a device the server no longer has is dropped and its replacement reached in the same send`() = runBlocking {
        alice.send(bob, "first")
        server.remove(bob)
        val bobNew = server.register(bob.userId)

        assertEquals(listOf(accepted(bobNew)), alice.send(bob, "second"))
        assertEquals("second", bobNew.receive().single().message.appEntry.id)
        assertEquals(listOf(bobNew.deviceId), alice.directory.devicesOf(bob.userId).map { it.deviceId })

        alice.send(bob, "third")
        assertEquals(2, server.bundleFetches(bob.userId))
    }

    @Test
    fun `self sync never targets this device and a single device fetches nothing`() = runBlocking {
        alice.directory.add(DeviceData(alice.userId, alice.deviceId))

        assertEquals(emptyList<SubmissionResult>(), alice.send(alice, "note"))
        assertEquals(0, server.bundleFetches(alice.userId))
        assertEquals(emptyList<Received>(), alice.receive())
    }

    @Test
    fun `self sync reaches the user's other devices`() = runBlocking {
        val alicePhone = server.register(alice.userId)

        assertEquals(listOf(accepted(alicePhone)), alice.send(alice, "note"))
        assertEquals("note", alicePhone.receive().single().message.appEntry.id)
        assertEquals(emptyList<Received>(), alice.receive())
    }

    @Test
    fun `concurrent first sends share one fetch`() = runBlocking {
        val results = (1..5).map { async { alice.send(bob, "c$it") } }.awaitAll()

        assertTrue(results.all { it == listOf(accepted(bob)) })
        assertEquals(1, server.bundleFetches(bob.userId))
        assertEquals(5, bob.receive().size)
    }

    @Test
    fun `a user with no devices is rejected without consuming anything else`() = runBlocking {
        val ghost = user()
        val result = alice.sender.send(ghost, entry("lost")).single()

        assertEquals(ghost, result.userId)
        assertEquals(null, result.deviceId)
        assertEquals(REJECTED, result.status)
    }

    @Test
    fun `devices with accepted evidence are not re-sent and need no session`() = runBlocking {
        alice.directory.add(DeviceData(bob.userId, bob.deviceId))

        assertEquals(listOf(accepted(bob)), alice.send(bob, "retry", accepted = setOf(bob.deviceId)))
        assertEquals(0, server.bundleFetches(bob.userId))
        assertEquals(emptyList<Received>(), bob.receive())
    }

    @Test
    fun `an unusable bundle is rejected once and does not cause a fetch on every send`() = runBlocking {
        val broken = server.register(bob.userId, corruptBundle = true)

        val first = alice.send(bob, "one")
        assertEquals(accepted(bob), first.single { it.deviceId == bob.deviceId })
        assertEquals(REJECTED, first.single { it.deviceId == broken.deviceId }.status)

        assertEquals(listOf(accepted(bob)), alice.send(bob, "two"))
        assertEquals(1, server.bundleFetches(bob.userId))
    }

    @Test
    fun `a changed identity is accepted, reported, and reached with a fresh session`() = runBlocking {
        val changes = mutableListOf<String>()
        alice.signalStore.onIdentityChanged = { address, _, _ -> changes += address }
        alice.send(bob, "before")
        bob.receive()

        bob.rekey()
        alice.signalStore.deleteAllSessions()

        assertEquals(listOf(accepted(bob)), alice.send(bob, "after"))
        assertEquals("after", bob.receive().single().message.appEntry.id)
        assertEquals(listOf("${bob.deviceId}.1"), changes)
        assertEquals(2, server.bundleFetches(bob.userId))
    }

    @Test
    fun `a peer's new identity in an incoming prekey message replaces the old session`() = runBlocking {
        alice.send(bob, "before")
        bob.receive()
        bob.send(alice, "ack")
        alice.receive()

        bob.rekey()
        bob.signalStore.deleteAllSessions()
        bob.send(alice, "from-new-identity")
        assertEquals("from-new-identity", alice.receive().single().message.appEntry.id)

        alice.send(bob, "to-new-identity")
        assertEquals("to-new-identity", bob.receive().single().message.appEntry.id)
        assertEquals(1, server.bundleFetches(bob.userId))
    }
}
