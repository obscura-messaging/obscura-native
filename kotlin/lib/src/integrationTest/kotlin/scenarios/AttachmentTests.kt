package scenarios

import dev.barrelmaker.obscura.kit.AuthState
import dev.barrelmaker.obscura.kit.ConnectionState
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class AttachmentTests {
    @Test
    fun `upload and download attachment content matches`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("a6")
        assertEquals(AuthState.AUTHENTICATED, alice.authState.value)
        assertEquals(ConnectionState.CONNECTED, alice.connectionState.value)

        val payload = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(200)
        val att = alice.uploadAttachment(payload)
        assertTrue(att.id.isNotEmpty())

        // The server holds ciphertext only: the kit encrypted before upload.
        val stored = alice.downloadAttachment(att.id)
        assertFalse(stored.contentEquals(payload), "server must never receive plaintext")

        val downloaded = alice.downloadDecryptedAttachment(att.id, att.contentKey, att.nonce)
        assertArrayEquals(payload, downloaded)

        alice.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, alice.connectionState.value)
    }
}
