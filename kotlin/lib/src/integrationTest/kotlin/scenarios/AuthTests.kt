package scenarios

import dev.barrelmaker.obscura.kit.AuthState
import dev.barrelmaker.obscura.kit.ConnectionState
import dev.barrelmaker.obscura.kit.LoginScenario
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class AuthTests {

    @Test
    fun `register authenticates, persists the session and logs in as the existing device`() = runBlocking {
        requireServer()
        val storage = MemoryStorage()
        val client = newClient(storage = storage)
        val username = uniqueName("reg")

        client.register(username, TEST_PASSWORD)

        assertEquals(AuthState.AUTHENTICATED, client.authState.value)
        assertEquals(username, client.username)
        assertEquals(setOf("token", "refreshToken", "userId", "deviceId", "username"), storage.data!!.keys)
        assertEquals(client.deviceId, storage.data!!["deviceId"])

        val userId = client.userId
        val deviceId = client.deviceId
        assertEquals(LoginScenario.EXISTING_DEVICE, client.login(username, TEST_PASSWORD))
        assertEquals(userId, client.userId)
        assertEquals(deviceId, client.deviceId)
    }

    @Test
    fun `login without a local device leaves the client logged out`() = runBlocking {
        requireServer()
        val username = uniqueName("login_out")
        newClient().register(username, TEST_PASSWORD)

        val fresh = newClient()
        assertEquals(LoginScenario.NEW_DEVICE, fresh.login(username, TEST_PASSWORD))
        assertEquals(LoginScenario.INVALID_CREDENTIALS, fresh.login(username, TEST_PASSWORD + "x"))
        assertEquals(LoginScenario.INVALID_CREDENTIALS, fresh.login(uniqueName("login_none"), TEST_PASSWORD))
        assertEquals(AuthState.LOGGED_OUT, fresh.authState.value)
        assertNull(fresh.userId)
    }

    @Test
    fun `loginAndProvision adds an authenticated second device`() = runBlocking {
        requireServer()
        val first = registerAndConnect("lap")
        val second = newClient(deviceName = "Second")

        second.loginAndProvision(first.username!!, TEST_PASSWORD)
        second.connect()

        assertEquals(AuthState.AUTHENTICATED, second.authState.value)
        assertEquals(first.userId, second.userId)
        assertNotEquals(first.deviceId, second.deviceId)
        first.disconnect(); second.disconnect()
    }

    @Test
    fun `a restarted client restores the stored session and receives what was queued`() = runBlocking {
        requireServer()
        val dbPath = File.createTempFile("obscura_bob_", ".db").absolutePath
        val storage = MemoryStorage()
        val alice = registerAndConnect("restore_a")
        val bob1 = registerAndConnect("restore_b", newClient(databasePath = dbPath, storage = storage))
        becomeFriends(alice, bob1)
        sendAndVerify(alice, bob1, "before restart")
        bob1.disconnect()
        sendText(alice, bob1, "while you were gone")

        val bob2 = newClient(databasePath = dbPath, storage = storage)
        assertTrue(bob2.restorePersistedSession())

        assertEquals(ConnectionState.CONNECTED, bob2.connectionState.value)
        assertEquals(bob1.deviceId, bob2.deviceId)
        bob2.awaitContent("while you were gone")
        assertNotNull(bob2.inbox.peek(500).find { it.content() == "before restart" })
        sendAndVerify(bob2, alice, "I'm back")
        alice.disconnect(); bob2.disconnect()
    }

    @Test
    fun `a refreshed token keeps the device scope`() = runBlocking {
        requireServer()
        val client = registerAndConnect("scope")
        client.disconnect()

        assertEquals(dev.barrelmaker.obscura.kit.managers.TokenStatus.FRESH, client.auth.refresh())

        client.connect()
        assertEquals(ConnectionState.CONNECTED, client.connectionState.value)
        client.disconnect()
    }

    @Test
    fun `logout revokes the session so a stored copy cannot be restored`() = runBlocking {
        requireServer()
        val storage = MemoryStorage()
        val client = registerAndConnect("logout", newClient(storage = storage))
        val stolen = storage.data!!

        client.logout()

        assertEquals(AuthState.LOGGED_OUT, client.authState.value)
        assertEquals(ConnectionState.DISCONNECTED, client.connectionState.value)
        assertNull(storage.data)
        // Force a refresh by presenting an expired access token with the revoked refresh token.
        val copy = MemoryStorage(stolen + ("token" to "x.e30.x"))
        assertFalse(newClient(storage = copy).restorePersistedSession())
        assertNull(copy.data)
    }

    @Test
    fun `after a wipe the account needs a new device`() = runBlocking {
        requireServer()
        val client = registerAndConnect("wipe")
        val username = client.username!!

        client.wipeDevice()

        assertEquals(AuthState.LOGGED_OUT, client.authState.value)
        assertEquals(LoginScenario.NEW_DEVICE, client.login(username, TEST_PASSWORD))
        client.loginAndProvision(username, TEST_PASSWORD)
        assertEquals(AuthState.AUTHENTICATED, client.authState.value)
    }
}
