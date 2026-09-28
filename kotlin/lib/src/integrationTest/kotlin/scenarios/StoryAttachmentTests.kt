package scenarios

import dev.barrelmaker.obscura.kit.AuthState
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * Scenario 10: Story with attachments E2E.
 * Full lifecycle: register, befriend via becomeFriends(), story with image, text-only, combo, sequence.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class StoryAttachmentTests {

    @Test @Order(1)
    fun `10-1 - Story with image synced, friend downloads attachment`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("s10a")
        val bob = registerAndConnect("s10b")
        assertEquals(AuthState.AUTHENTICATED, alice.authState.value)
        assertEquals(AuthState.AUTHENTICATED, bob.authState.value)

        becomeFriends(alice, bob)

        // Upload JPEG image
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(250)
        val att = alice.uploadAttachment(jpeg)
        val attId = att.id
        assertTrue(attId.isNotEmpty())

        // Send story with media reference
        val storyId = "story_${System.currentTimeMillis()}"
        alice.sendStory(bob, storyId,
            mapOf("content" to "My vacation", "mediaRef" to attId, "mimeType" to "image/jpeg"))

        // Bob receives APP_ENTRY
        val msg = bob.waitForType("APP_ENTRY")
        assertEquals(alice.userId, msg.sourceUserId)

        val storyData = JSONObject(String(msg.raw!!.appEntry.data.toByteArray()))
        assertEquals("My vacation", storyData.getString("content"))
        assertEquals(attId, storyData.getString("mediaRef"))
        assertEquals("image/jpeg", storyData.getString("mimeType"))

        // Bob downloads the attachment and verifies
        val downloaded = bob.downloadDecryptedAttachment(attId, att.contentKey, att.nonce)
        assertEquals(jpeg.size, downloaded.size, "Downloaded size must match")
        assertEquals(0xFF.toByte(), downloaded[0], "JPEG SOI marker byte 1")
        assertEquals(0xD8.toByte(), downloaded[1], "JPEG SOI marker byte 2")
        assertArrayEquals(jpeg, downloaded, "Full content must match")

        alice.disconnect()
        bob.disconnect()
    }

    @Test @Order(2)
    fun `10-2 - Text-only story syncs correctly`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("s10ta")
        val bob = registerAndConnect("s10tb")
        becomeFriends(alice, bob)

        alice.sendStory(bob, "story_text_${System.currentTimeMillis()}",
            mapOf("content" to "Just text, no media"))

        val msg = bob.waitForType("APP_ENTRY")
        assertEquals(alice.userId, msg.sourceUserId)
        assertEquals("story", msg.raw!!.appEntry.model)

        val d = JSONObject(String(msg.raw!!.appEntry.data.toByteArray()))
        assertEquals("Just text, no media", d.getString("content"))
        assertFalse(d.has("mediaRef"), "Text-only story should not have mediaRef")

        alice.disconnect()
        bob.disconnect()
    }

    @Test @Order(3)
    fun `10-3 - Text and image combo story`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("s10ca")
        val bob = registerAndConnect("s10cb")
        becomeFriends(alice, bob)

        // Upload image
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(500)
        val att = alice.uploadAttachment(jpeg)
        val attId = att.id

        // Send story with both text and image
        alice.sendStory(bob, "story_combo_${System.currentTimeMillis()}",
            mapOf("content" to "check out this sunset", "mediaRef" to attId, "contentType" to "image/jpeg"))

        val msg = bob.waitForType("APP_ENTRY")
        assertEquals(alice.userId, msg.sourceUserId)

        // Verify both text and media fields present
        val data = JSONObject(String(msg.raw!!.appEntry.data.toByteArray()))
        assertEquals("check out this sunset", data.getString("content"), "Text content should match")
        assertEquals(attId, data.getString("mediaRef"), "Media ref should match attachment ID")
        assertEquals("image/jpeg", data.getString("contentType"), "Content type should be image/jpeg")

        // Verify attachment downloadable
        val downloaded = bob.downloadDecryptedAttachment(attId, att.contentKey, att.nonce)
        assertEquals(jpeg.size, downloaded.size)
        assertArrayEquals(jpeg, downloaded)

        alice.disconnect()
        bob.disconnect()
    }

    @Test @Order(4)
    fun `10-4 - Multiple stories in sequence all received`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("s10ma")
        val bob = registerAndConnect("s10mb")
        becomeFriends(alice, bob)

        // Send 3 stories in sequence
        for (i in 1..3) {
            alice.sendStory(bob, "seq_$i", mapOf("content" to "Story $i"))
        }

        // Receive all 3 and verify each
        val received = mutableListOf<String>()
        for (i in 1..3) {
            val msg = bob.waitForType("APP_ENTRY")
            assertEquals("story", msg.raw!!.appEntry.model, "Model should be 'story'")
            assertEquals(alice.userId, msg.sourceUserId, "Source should be alice")
            val d = JSONObject(String(msg.raw!!.appEntry.data.toByteArray()))
            received.add(d.getString("content"))
        }

        assertEquals(3, received.size, "Should receive exactly 3 stories")
        assertTrue(received.contains("Story 1"), "Should contain Story 1")
        assertTrue(received.contains("Story 2"), "Should contain Story 2")
        assertTrue(received.contains("Story 3"), "Should contain Story 3")

        alice.disconnect()
        bob.disconnect()
    }
}
