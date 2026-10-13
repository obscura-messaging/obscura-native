package dev.barrelmaker.obscura.kit

import dev.barrelmaker.obscura.kit.stores.FriendStatus
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FacadePullEventsTest {

    @Test
    fun `friend wakes are payload free and hosts pull current rows`() = runBlocking {
        val client = ObscuraClient(
            ObscuraConfig(apiUrl = "https://example.com", authRateLimitDelayMs = 0),
        )

        withTimeout(5_000) { client.friendsChanged.first() }
        assertTrue(client.getFriends().isEmpty())

        val changed = async(start = CoroutineStart.UNDISPATCHED) {
            client.friendsChanged.drop(1).first()
        }
        val now = System.currentTimeMillis()
        client.db.friendQueries.insert(
            user_id = "alice-id",
            username = "alice",
            status = FriendStatus.ACCEPTED.value,
            created_at = now,
            updated_at = now,
        )
        withTimeout(5_000) { changed.await() }

        assertEquals(listOf("alice-id"), client.getFriends().map { it.userId })
    }

    @Test
    fun `debug output is pulled from the bounded ring`() {
        val client = ObscuraClient(
            ObscuraConfig(apiUrl = "https://example.com", authRateLimitDelayMs = 0),
        )

        client.disconnect()

        assertTrue(client.getDebugLog().any { it.contains("DISCONNECT") })
    }
}
