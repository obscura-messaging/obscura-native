package dev.barrelmaker.obscura.kit

import dev.barrelmaker.obscura.kit.crypto.LinkCrypto
import dev.barrelmaker.obscura.kit.crypto.fromBase64
import dev.barrelmaker.obscura.kit.stores.FriendData
import dev.barrelmaker.obscura.kit.stores.FriendStatus
import dev.barrelmaker.obscura.kit.stores.StoredEntry
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.signal.libsignal.protocol.ecc.Curve
import java.util.Collections
import java.util.UUID

/** Login outcomes, primary provisioning, reset and linking against a stand-in server. */
class AccountTest {
    private val newDeviceId = "019f025a-0000-7de6-84b6-0000000000d9"
    private val userToken = jwt(SELF_USER_ID, FAR_FUTURE)

    /** The server's account state for one user. */
    private class Account {
        var devices = listOf<Pair<String, Boolean>>()
        var loginDeviceId: String? = null
        var linkBody: ByteArray? = null
        var linkStatus = 200
        val attachments = Collections.synchronizedMap(mutableMapOf<String, ByteArray>())
        val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
    }

    private val account = Account()
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                account.requests.add(request)
                val path = request.url.encodedPath
                return when {
                    path == "/v1/sessions" -> json(JSONObject().put("token", userToken)
                        .apply { account.loginDeviceId?.let { put("refreshToken", "rt").put("deviceId", it) } })
                    path == "/v1/account/reset" -> MockResponse.Builder().build()
                    path == "/v1/devices" && request.method == "GET" -> json(JSONObject().put("devices",
                        account.devices.map { (id, primary) -> JSONObject().put("deviceId", id).put("isPrimary", primary) }))
                    path == "/v1/devices" -> json(JSONObject().put("token", userToken).put("refreshToken", "rt")
                        .put("deviceId", if (JSONObject(request.body!!.utf8()).has("linkSessionId")) newDeviceId else SELF_DEVICE_ID))
                    path == "/v1/link-sessions" -> json(JSONObject().put("id", "ls-1").put("expiresAt", System.currentTimeMillis() / 1000 + 600))
                    path == "/v1/link-sessions/ls-1" && request.method == "PUT" && account.linkStatus != 200 ->
                        MockResponse.Builder().code(account.linkStatus).build()
                    path == "/v1/link-sessions/ls-1" && request.method == "PUT" -> {
                        account.linkBody = request.body!!.toByteArray()
                        MockResponse.Builder().build()
                    }
                    path == "/v1/link-sessions/ls-1" -> when {
                        account.linkStatus != 200 -> MockResponse.Builder().code(account.linkStatus).build()
                        account.linkBody == null -> MockResponse.Builder().code(204).build()
                        else -> MockResponse.Builder().body(okio.Buffer().write(account.linkBody!!)).build()
                    }
                    path == "/v1/attachments" -> {
                        val id = UUID.randomUUID().toString()
                        account.attachments[id] = request.body!!.toByteArray()
                        json(JSONObject().put("id", id))
                    }
                    path.startsWith("/v1/attachments/") ->
                        MockResponse.Builder().body(okio.Buffer().write(account.attachments.getValue(path.removePrefix("/v1/attachments/")))).build()
                    path == "/v1/gateway/ticket" -> json(JSONObject().put("ticket", "t"))
                    path == "/v1/gateway" -> MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
                    }).build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        start()
    }
    private val url = server.url("/").toString().trimEnd('/')

    @AfterEach fun close() = server.close()

    private fun json(body: JSONObject) = MockResponse.Builder().body(body.toString()).build()

    private fun requestsTo(path: String) = account.requests.filter { it.url.encodedPath == path }

    @Test fun `without a stored device the account's devices decide between linking and a new primary`() = runBlocking {
        val storage = MemoryStorage()
        val c = newClient(url, storage)

        assertEquals(LoginScenario.NO_DEVICES, c.login("self", "password"))
        account.devices = listOf(SELF_DEVICE_ID to true)
        assertEquals(LoginScenario.LINK_REQUIRED, c.login("self", "password"))

        assertEquals(AuthState.LOGGED_OUT, c.authState.value)
        assertNull(storage.data)
        assertTrue(requestsTo("/v1/devices").all { it.headers["Authorization"] == "Bearer $userToken" })
        assertFalse(requestsTo("/v1/sessions").any { JSONObject(it.body!!.utf8()).has("deviceId") })
    }

    @Test fun `a stored device the account no longer has is a device mismatch`() = runBlocking {
        val c = sessionClient(url, MemoryStorage())

        assertEquals(LoginScenario.DEVICE_MISMATCH, c.login("self", "password"))
        assertEquals(AuthState.LOGGED_OUT, c.authState.value)

        account.loginDeviceId = SELF_DEVICE_ID
        assertEquals(LoginScenario.EXISTING_DEVICE, c.login("self", "password"))
        assertEquals(SELF_DEVICE_ID, c.deviceId)
    }

    @Test fun `provisionPrimary stores a primary with a new identity once the server accepts it`() = runBlocking {
        val storage = MemoryStorage()
        val c = newClient(url, storage)

        c.provisionPrimary("self", "password")

        val body = JSONObject(requestsTo("/v1/devices").single().body!!.utf8())
        assertFalse(body.has("linkSessionId"))
        assertArrayEquals(c.signalStore.identityKeyPair.publicKey.serialize(), body.getString("identityKey").fromBase64())
        assertEquals(100, body.getJSONArray("oneTimePreKeys").length())
        assertEquals(1, body.getJSONObject("signedPreKey").getInt("keyId"))
        assertTrue(c.isPrimary())
        assertEquals(listOf(SELF_DEVICE_ID), c.devices.devicesOf(SELF_USER_ID))
        assertEquals(SELF_DEVICE_ID, storage.data!!["deviceId"])
        assertEquals(AuthState.AUTHENTICATED, c.authState.value)
        c.auth.end()
        Unit
    }

    @Test fun `resetAccount resets the account on the server, then wipes this device`() = runBlocking {
        val storage = storedSession()
        val c = sessionClient(url, storage)
        assertTrue(c.auth.restore())
        c.entries.put("m", StoredEntry("e", "{}", 1, SELF_DEVICE_ID))

        c.resetAccount("self", "password")

        assertEquals(JSONObject().put("username", "self").put("password", "password").toString(),
            requestsTo("/v1/account/reset").single().body!!.utf8())
        assertEquals(AuthState.LOGGED_OUT, c.authState.value)
        assertNull(storage.data)
        assertNull(c.devices.getIdentity())
        assertFalse(c.signalStore.hasIdentity())
        assertTrue(c.entries.all("m").isEmpty())
    }

    @Test fun `a linked device receives the account identity, friends and history and is not primary`() = runBlocking {
        val primary = sessionClient(url, storedSession())
        assertTrue(primary.auth.restore())
        val friends = listOf(FriendData("019f025a-0000-7de6-84b6-000000000002", "alice", FriendStatus.ACCEPTED))
        primary.db.friendQueries.insert(friends[0].userId, friends[0].username, friends[0].status.value)
        primary.db.friendQueries.incrementVersion()
        primary.entries.put("chat", StoredEntry("e1", """{"text":"hi"}""", 42, SELF_DEVICE_ID, localMetadata = "private"))
        val storage = MemoryStorage()
        val tablet = newClient(url, storage)

        val request = tablet.beginLink("self", "password", "Tablet")
        val code = LinkCrypto.decodeCode(request.code)
        assertEquals("ls-1", code.sessionId)
        assertEquals("Tablet", code.deviceName)
        assertEquals(33, Curve.decodePoint(code.ephemeralPublicKey.toByteArray(), 0).serialize().size)
        primary.approveLink(request.code)
        tablet.completeLink(request)

        assertArrayEquals(primary.signalStore.identityKeyPair.serialize(), tablet.signalStore.identityKeyPair.serialize())
        assertFalse(tablet.isPrimary())
        assertEquals(newDeviceId, tablet.deviceId)
        assertEquals(newDeviceId, storage.data!!["deviceId"])
        assertEquals(friends, tablet.getFriends())
        assertEquals(1L, tablet.db.friendQueries.selectVersion().executeAsOne())
        assertEquals(listOf(StoredEntry("e1", """{"text":"hi"}""", 42, SELF_DEVICE_ID)), tablet.entries.all("chat"))
        assertEquals(ConnectionState.CONNECTED, tablet.connectionState.value)
        val created = JSONObject(requestsTo("/v1/devices").single { it.method == "POST" }.body!!.utf8())
        assertEquals("ls-1", created.getString("linkSessionId"))
        assertEquals("Tablet", created.getString("name"))
        assertArrayEquals(primary.signalStore.identityKeyPair.publicKey.serialize(), created.getString("identityKey").fromBase64())
        assertTrue(primary.signalStore.identityKeyPair.publicKey.publicKey.verifySignature(
            created.getJSONObject("signedPreKey").getString("publicKey").fromBase64(),
            created.getJSONObject("signedPreKey").getString("signature").fromBase64()))
        assertTrue(account.attachments.values.none { String(it).contains("hi") }, "history is uploaded encrypted")
        tablet.disconnect()
        primary.auth.end()
        tablet.auth.end()
        Unit
    }

    @Test fun `an empty entry store links without a history snapshot`() = runBlocking {
        val primary = sessionClient(url, storedSession())
        assertTrue(primary.auth.restore())
        val tablet = newClient(url, MemoryStorage())
        val request = tablet.beginLink("self", "password", "Tablet")

        primary.approveLink(request.code)
        tablet.completeLink(request)

        assertTrue(account.attachments.isEmpty())
        assertTrue(tablet.getFriends().isEmpty())
        tablet.disconnect()
        primary.auth.end()
        tablet.auth.end()
        Unit
    }

    @Test fun `an envelope that does not decrypt fails the link and stores nothing`() = runBlocking {
        val tablet = newClient(url, MemoryStorage())
        val request = tablet.beginLink("self", "password", "Tablet")
        account.linkBody = LinkCrypto.seal(ByteArray(8), Curve.generateKeyPair().publicKey).toByteArray()

        val error = runCatching { tablet.completeLink(request) }.exceptionOrNull()

        assertTrue(error is ObscuraError.LinkFailed, "got $error")
        assertFalse(tablet.signalStore.hasIdentity())
        assertNull(tablet.devices.getIdentity())
        assertTrue(requestsTo("/v1/devices").isEmpty())
    }

    @Test fun `an expired or unknown link session fails the link`() = runBlocking {
        val tablet = newClient(url, MemoryStorage())
        val request = tablet.beginLink("self", "password", "Tablet")
        account.linkStatus = 404

        assertTrue(runCatching { tablet.completeLink(request) }.exceptionOrNull() is ObscuraError.LinkFailed)
        val primary = sessionClient(url, storedSession())
        assertTrue(primary.auth.restore())
        assertTrue(runCatching { primary.approveLink(request.code) }.exceptionOrNull() is ObscuraError.LinkFailed)
        primary.auth.end()
        Unit
    }

    @Test fun `a malformed link code is an invalid argument`() = runBlocking {
        val primary = sessionClient(url, storedSession())
        assertTrue(primary.auth.restore())
        for (code in listOf("", "not base64!", "AAAA=", LinkCrypto.encodeCode(obscura.client.v1.Client.LinkCode.newBuilder().setSessionId("s").build()))) {
            assertTrue(runCatching { primary.approveLink(code) }.exceptionOrNull() is ObscuraError.InvalidArgument, code)
        }
        primary.auth.end()
        Unit
    }

    @Test fun `linking requires an unprovisioned device`() = runBlocking {
        val c = sessionClient(url, MemoryStorage())
        assertTrue(runCatching { c.beginLink("self", "password", "Tablet") }.exceptionOrNull() is ObscuraError.InvalidArgument)
        assertTrue(runCatching { newClient(url).beginLink("self", "password", " ") }.exceptionOrNull() is ObscuraError.InvalidArgument)
        assertTrue(account.requests.isEmpty())
    }
}
