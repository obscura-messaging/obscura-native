package scenarios

import dev.barrelmaker.obscura.kit.AuthState
import dev.barrelmaker.obscura.kit.ConnectionState
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * Edge cases: attachment size limits and profile entry sync.
 * Full lifecycle with state verification after every mutation.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class EdgeCaseTests {

    @Test @Order(1)
    fun `EC-1 - Small attachment upload and download`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("eca")
        assertEquals(AuthState.AUTHENTICATED, alice.authState.value)
        assertEquals(ConnectionState.CONNECTED, alice.connectionState.value)

        val small = ByteArray(100) { it.toByte() }
        val att = alice.uploadAttachment(small)
        assertTrue(att.id.isNotEmpty(), "Attachment ID should be non-empty")

        val downloaded = alice.downloadDecryptedAttachment(att.id, att.contentKey, att.nonce)
        assertArrayEquals(small, downloaded, "Downloaded content must match uploaded")

        alice.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, alice.connectionState.value)
    }

    @Test @Order(2)
    fun `EC-2 - Medium attachment upload, download, and size verification`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("ecm")
        assertEquals(AuthState.AUTHENTICATED, alice.authState.value)

        val medium = ByteArray(500 * 1024) { (it % 256).toByte() } // 500KB
        val att = alice.uploadAttachment(medium)
        assertTrue(att.id.isNotEmpty(), "Attachment ID should be non-empty")

        val downloaded = alice.downloadDecryptedAttachment(att.id, att.contentKey, att.nonce)
        assertEquals(medium.size, downloaded.size, "Downloaded size should match 500KB")
        assertArrayEquals(medium, downloaded, "Downloaded content must match uploaded")

        alice.disconnect()
    }

    @Test @Order(4)
    fun `EC-4 - Profile data syncs via APP_ENTRY with full lifecycle`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("ecpa")
        val bob = registerAndConnect("ecpb")
        assertEquals(AuthState.AUTHENTICATED, alice.authState.value)
        assertEquals(AuthState.AUTHENTICATED, bob.authState.value)

        becomeFriends(alice, bob)

        // Alice sends profile APP_ENTRY
        alice.send(
            recipientUserIds = listOf(bob.userId!!),
            modelKey = "profile",
            entryId = "profile_${alice.userId}",
            payload = JSONObject(mapOf(
                "displayName" to "Alice Display", "avatarUrl" to "att-avatar-123",
            )).toString().toByteArray(),
        )

        // Bob receives and verifies
        val msg = bob.waitForType("APP_ENTRY")
        assertEquals(alice.userId, msg.sourceUserId, "Source should be alice")
        assertEquals("profile", msg.raw!!.appEntry.model, "Model should be 'profile'")

        val data = JSONObject(String(msg.raw!!.appEntry.data.toByteArray()))
        assertEquals("Alice Display", data.getString("displayName"), "Display name should match")
        assertEquals("att-avatar-123", data.getString("avatarUrl"), "Avatar URL should match")

        alice.disconnect()
        bob.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, alice.connectionState.value)
        assertEquals(ConnectionState.DISCONNECTED, bob.connectionState.value)
    }
}
