package scenarios

import dev.barrelmaker.obscura.kit.ObscuraClient
import dev.barrelmaker.obscura.kit.ObscuraConfig
import dev.barrelmaker.obscura.kit.AuthState
import dev.barrelmaker.obscura.kit.MessageWakeEvent
import dev.barrelmaker.obscura.kit.stores.FriendStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*

/** Test-only: suspend until the next inbound message (or time out). */
/**
 * The application content of a received entry.
 *
 * APP_ENTRY data is application-owned opaque payload bytes; reading `content` out of it is
 * something only a test (standing in for the app) may do, never the kit.
 */
fun MessageWakeEvent.content(): String =
    runCatching {
        org.json.JSONObject(String(raw!!.appEntry.data.toByteArray())).optString("content", "")
    }.getOrDefault("")

/**
 * Wait for the next message OF A PARTICULAR KIND, skipping anything else already queued.
 *
 * `waitForMessage` takes the head of `incomingMessages`. Tests that need a
 * specific control message skip unrelated droppable notifications explicitly.
 *
 * Skipping by kind is more honest than draining: the test says which message it is waiting for.
 */
suspend fun ObscuraClient.waitForType(type: String, timeoutMs: Long = 15_000): MessageWakeEvent =
    withTimeout(timeoutMs) {
        while (true) {
            val msg = incomingMessages.receive()
            if (msg.type == type) return@withTimeout msg
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

suspend fun ObscuraClient.waitForMessage(timeoutMs: Long = 15_000): MessageWakeEvent =
    withTimeout(timeoutMs) { incomingMessages.receive() }

// Override with OBSCURA_TEST_API to point at a locally containerized
// obscura-server (`docker compose up` → :3000) instead of the live server.
val API: String = System.getenv("OBSCURA_TEST_API") ?: "https://obscura.barrelmaker.dev"
const val TEST_PASSWORD = "testpass123!xyz"

fun checkServer(): Boolean = try {
    java.net.URL("$API/openapi.yaml").openConnection().apply {
        connectTimeout = 5000; readTimeout = 5000
    }.getInputStream().close(); true
} catch (e: Exception) { false }

fun uniqueName(prefix: String): String =
    "kt_${prefix}_${System.currentTimeMillis()}_${(1000..9999).random()}"

/**
 * Provision a second device and approve it from the first device.
 * Returns the second device in AUTHENTICATED state.
 */
suspend fun provisionAndApprove(existingDevice: ObscuraClient, username: String, deviceName: String = "Device 2"): ObscuraClient {
    val device2 = ObscuraClient(ObscuraConfig(API, deviceName = deviceName))
    device2.loginAndProvision(username, TEST_PASSWORD, deviceName)
    assertEquals(AuthState.PENDING_APPROVAL, device2.authState.value)

    // Device 2 connects and generates link code
    device2.connect()
    val linkCode = device2.generateLinkCode()

    // Existing device validates and approves
    existingDevice.validateAndApproveLink(linkCode)

    // Device 2 receives the approval — drain messages until state changes
    val deadline = System.currentTimeMillis() + 15_000
    while (device2.authState.value == AuthState.PENDING_APPROVAL && System.currentTimeMillis() < deadline) {
        try { device2.waitForMessage(2_000) } catch (_: Exception) {}
    }
    assertEquals(AuthState.AUTHENTICATED, device2.authState.value,
        "Device should be AUTHENTICATED after approval")

    return device2
}

suspend fun registerAndConnect(prefix: String, config: ObscuraConfig = ObscuraConfig(API)): ObscuraClient {
    val client = ObscuraClient(config)
    client.register(uniqueName(prefix), TEST_PASSWORD)
    assertEquals(AuthState.AUTHENTICATED, client.authState.value)
    assertNotNull(client.userId)
    assertNotNull(client.deviceId)
    client.connect()
    return client
}

suspend fun becomeFriends(a: ObscuraClient, b: ObscuraClient) {
    val aFriendsBefore = a.friendList.value.size
    val bFriendsBefore = b.friendList.value.size

    a.befriend(b.userId!!, b.username!!)
    delay(300)

    b.waitForMessage() // FRIEND_REQUEST
    delay(300)
    assertTrue(b.friendList.value.any {
        it.userId == a.userId && it.status == FriendStatus.PENDING_RECEIVED
    },
        "Receiver should see pending request from sender")

    b.acceptFriend(a.userId!!)
    a.waitForMessage() // FRIEND_ACCEPT
    delay(300)

    assertTrue(a.friendList.value.any { it.userId == b.userId && it.status == FriendStatus.ACCEPTED },
        "Sender should see receiver as ACCEPTED friend")
    assertTrue(b.friendList.value.any { it.userId == a.userId && it.status == FriendStatus.ACCEPTED },
        "Receiver should see sender as ACCEPTED friend")
    assertEquals(aFriendsBefore + 1, a.friendList.value.size)
    assertEquals(bFriendsBefore + 1, b.friendList.value.size)
}

/**
 * Send a message the way obscura-pix does, and assert it arrived.
 *
 * The helper names the recipient by userId, carries a canonical conversation
 * id, and reads delivery from the receiver's inbox.
 */
/**
 * Send without asserting delivery — for tests where the receiver is deliberately OFFLINE and the
 * point is that the SERVER queues it. [sendAndVerify] polls the receiver's inbox, which cannot
 * succeed until they reconnect.
 */
/** Whether [client] has received an entry whose payload carries [content]. */
suspend fun ObscuraClient.hasReceived(content: String, timeoutMs: Long = 10_000): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val hit = inbox.peek(200).any {
            runCatching {
                org.json.JSONObject(String(it.payload)).optString("content", "") == content
            }.getOrDefault(false)
        }
        if (hit) return true
        delay(250)
    }
    return false
}

/**
 * Send a `story` entry the way obscura-pix does: the caller names the recipient and the
 * body is opaque payload bytes the kit never opens.
 */
suspend fun ObscuraClient.sendStory(receiver: ObscuraClient, entryId: String, data: Map<String, Any?>) {
    send(
        recipientUserIds = listOf(receiver.userId!!),
        modelKey = "story",
        entryId = entryId,
        payload = org.json.JSONObject(data).toString().toByteArray(),
    )
}

suspend fun sendOnly(sender: ObscuraClient, receiver: ObscuraClient, text: String): String {
    val entryId = "dm_${System.currentTimeMillis()}_${(0..99999).random()}"
    sender.send(
        recipientUserIds = listOf(receiver.userId!!),
        modelKey = "directMessage",
        entryId = entryId,
        payload = org.json.JSONObject(mapOf(
            "conversationId" to listOf(sender.userId!!, receiver.userId!!).sorted().joinToString("_"),
            "content" to text,
            "senderUsername" to (sender.username ?: ""),
        )).toString().toByteArray(),
    )
    return entryId
}

suspend fun sendAndVerify(sender: ObscuraClient, receiver: ObscuraClient, text: String, timeoutMs: Long = 15_000) {
    val convId = listOf(sender.userId!!, receiver.userId!!).sorted().joinToString("_")
    val entryId = "dm_${System.currentTimeMillis()}_${(0..99999).random()}"
    sender.send(
        recipientUserIds = listOf(receiver.userId!!),
        modelKey = "directMessage",
        entryId = entryId,
        payload = org.json.JSONObject(mapOf(
            "conversationId" to convId,
            "content" to text,
            "senderUsername" to (sender.username ?: ""),
        )).toString().toByteArray(),
    )

    // Poll the INBOX rather than consuming from `incomingMessages`: the channel is a droppable
    // wake-up and the row is the delivery path. Consuming a channel item would also steal it from
    // callers that wait for a specific notification afterwards.
    val deadline = System.currentTimeMillis() + timeoutMs
    var row: dev.barrelmaker.obscura.kit.stores.InboxRecord? = null
    while (System.currentTimeMillis() < deadline && row == null) {
        row = receiver.inbox.peek(200).find { it.entryId == entryId }
        if (row == null) delay(250)
    }

    assertTrue(row != null, "receiver's inbox should contain entry $entryId within ${timeoutMs}ms")
    assertEquals(sender.userId, row!!.senderUserId)
    assertEquals(text, org.json.JSONObject(String(row.payload)).getString("content"))
}
