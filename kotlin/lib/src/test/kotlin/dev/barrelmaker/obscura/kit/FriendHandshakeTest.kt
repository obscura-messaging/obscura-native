package dev.barrelmaker.obscura.kit

import dev.barrelmaker.obscura.kit.crypto.UuidCodec
import dev.barrelmaker.obscura.kit.managers.FakeObscuraServer
import dev.barrelmaker.obscura.kit.managers.SignalKeyUtils
import dev.barrelmaker.obscura.kit.network.DeviceKeys
import dev.barrelmaker.obscura.kit.stores.FriendStatus
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import obscura.client.v1.Client.ClientMessage
import org.json.JSONArray
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import xyz.obscura.server.contracts.ObscuraProtocol.Envelope
import xyz.obscura.server.contracts.ObscuraProtocol.SendMessageRequest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class FriendHandshakeTest {
    private fun DeviceKeys.bundle(deviceId: String) = toJson().put("deviceId", deviceId)
        .put("oneTimePreKey", oneTimePreKeys.first().toJson())

    private fun envelope(userId: String, deviceId: String, submission: SendMessageRequest.Submission) =
        Envelope.newBuilder().setId(UuidCodec.uuidToBytes(UUID.randomUUID()))
            .setSenderId(UuidCodec.uuidToBytes(UUID.fromString(userId)))
            .setSenderDeviceId(UuidCodec.uuidToBytes(UUID.fromString(deviceId)))
            .setMessage(submission.message).build()

    @Test fun `a fast encrypted acceptance before a failed request response is never rolled back`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            FakeObscuraServer().use { devices ->
                val remote = devices.register(UUID.randomUUID().toString())
                val client = sessionClient(server.url("/").toString(), storedSession())
                SignalKeyUtils.generateDeviceKeys(client.signalStore)
                assertTrue(client.auth.restore())
                val stateBeforeAcceptance = AtomicReference<FriendStatus?>()
                val stateBeforeResponse = AtomicReference<FriendStatus?>()
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.url.encodedPath != "/v1/messages") {
                            return MockResponse.Builder().body(JSONArray().put(remote.consumeBundle()).toString()).build()
                        }
                        runBlocking {
                            stateBeforeAcceptance.set(client.getFriends().singleOrNull()?.status)
                            val sent = SendMessageRequest.parseFrom(request.body!!.toByteArray()).messagesList.single()
                            remote.messenger.decrypt(SELF_DEVICE_ID, sent.message)
                            val accept = ClientMessage.newBuilder().setFriendAccept(obscura.client.v1.friendAccept {}).build()
                            val reply = remote.messenger.prepareSubmission(SELF_DEVICE_ID, accept)
                            client.receive(envelope(remote.userId, remote.deviceId, reply)) {}
                            stateBeforeResponse.set(client.getFriends().singleOrNull()?.status)
                        }
                        return MockResponse.Builder().code(500).body("request response unavailable").build()
                    }
                }

                val error = runCatching {
                    client.addFriendByCode(FriendCode.encode(remote.userId, "label from code"))
                }.exceptionOrNull()

                assertTrue(error is ObscuraError.SendFailed)
                assertEquals(FriendStatus.PENDING_SENT, stateBeforeAcceptance.get())
                assertEquals(FriendStatus.ACCEPTED, stateBeforeResponse.get())
                assertEquals(FriendStatus.ACCEPTED, client.getFriends().single().status)
                assertEquals("label from code", client.getFriends().single().username)
                client.auth.end()
                Unit
            }
        }
    }

    @Test fun `an independent installation explicitly re-adds an accepted peer without replacing its name or old relationship`() = runBlocking {
        MockWebServer().use { peerServer ->
            MockWebServer().use { newServer ->
                FakeObscuraServer().use { otherDevices ->
                    peerServer.start()
                    newServer.start()
                    val peer = sessionClient(peerServer.url("/").toString(), storedSession())
                    val peerKeys = SignalKeyUtils.generateDeviceKeys(peer.signalStore)
                    assertTrue(peer.auth.restore())
                    val accountId = UUID.randomUUID().toString()
                    val oldDevice = otherDevices.register(accountId)
                    val newDeviceId = UUID.randomUUID().toString()
                    val storage = MemoryStorage(mapOf(
                        "token" to jwt(accountId, FAR_FUTURE), "refreshToken" to "new-rt",
                        "userId" to accountId, "deviceId" to newDeviceId, "username" to "new claimed name",
                    ))
                    val fresh = newClient(newServer.url("/").toString(), storage)
                    val newKeys = SignalKeyUtils.generateDeviceKeys(fresh.signalStore)
                    fresh.devices.storeIdentity(newDeviceId)
                    assertTrue(fresh.auth.restore())
                    assertTrue(fresh.getFriends().isEmpty())
                    peer.db.friendQueries.insert(accountId, "original trusted name", FriendStatus.ACCEPTED.value)
                    peer.devices.add(accountId, oldDevice.deviceId)
                    val original = peer.getFriends().single()
                    val stateAtSubmission = AtomicReference<FriendStatus?>()
                    val outgoing = AtomicReference<SendMessageRequest>()
                    newServer.dispatcher = object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse =
                            if (request.url.encodedPath == "/v1/messages") {
                                stateAtSubmission.set(runBlocking { fresh.getFriends().single().status })
                                outgoing.set(SendMessageRequest.parseFrom(request.body!!.toByteArray()))
                                MockResponse.Builder().body("").build()
                            } else {
                                MockResponse.Builder().body(JSONArray().put(peerKeys.bundle(SELF_DEVICE_ID)).toString()).build()
                            }
                    }

                    fresh.addFriendByCode(peer.friendCode())
                    assertEquals(FriendStatus.PENDING_SENT, stateAtSubmission.get())
                    assertEquals(FriendStatus.PENDING_SENT, fresh.getFriends().single().status)

                    peerServer.enqueue(MockResponse.Builder().body(
                        JSONArray().put(oldDevice.consumeBundle()).put(newKeys.bundle(newDeviceId)).toString()
                    ).build())
                    peerServer.enqueue(MockResponse.Builder().body("").build())
                    var requestAcked = false
                    peer.receive(envelope(accountId, newDeviceId, outgoing.get().messagesList.single())) { requestAcked = true }
                    assertTrue(requestAcked)
                    assertEquals(original, peer.getFriends().single())
                    assertEquals("/v1/users/$accountId", peerServer.takeRequest().url.encodedPath)
                    val reply = SendMessageRequest.parseFrom(peerServer.takeRequest().body!!.toByteArray())
                    assertEquals(setOf(oldDevice.deviceId, newDeviceId),
                        reply.messagesList.map { UuidCodec.uuidString(it.deviceId) }.toSet())
                    val addressedToNew = reply.messagesList.single { UuidCodec.uuidString(it.deviceId) == newDeviceId }
                    var replyAcked = false
                    fresh.receive(envelope(SELF_USER_ID, SELF_DEVICE_ID, addressedToNew)) { replyAcked = true }
                    assertTrue(replyAcked)
                    assertEquals(FriendStatus.ACCEPTED, fresh.getFriends().single().status)
                    assertEquals(original, peer.getFriends().single())
                    assertEquals(0L, fresh.inbox.depth())
                    assertTrue(fresh.entries.all("m").isEmpty())
                    peer.auth.end()
                    fresh.auth.end()
                    Unit
                }
            }
        }
    }
}
