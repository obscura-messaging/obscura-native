package dev.barrelmaker.obscura.kit.stores

import dev.barrelmaker.obscura.kit.newInMemoryDatabase
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** FriendStore is the source of truth for who the user knows and the friendship state. */
class FriendStoreTest {

    private fun newDomain() = FriendStore(newInMemoryDatabase())

    @Test
    fun `add then get returns the friend`() = runTest {
        val d = newDomain()
        d.add("u1", "alice", FriendStatus.ACCEPTED)
        assertEquals(FriendData("u1", "alice", FriendStatus.ACCEPTED), d.get("u1"))
    }

    @Test
    fun `get returns null for a user who was never added`() = runTest {
        assertNull(newDomain().get("nope"))
    }

    @Test
    fun `getAccepted filters out pending friends`() = runTest {
        val d = newDomain()
        d.add("u1", "alice", FriendStatus.ACCEPTED)
        d.add("u2", "bob", FriendStatus.PENDING_SENT)
        d.add("u3", "carol", FriendStatus.PENDING_RECEIVED)

        assertEquals(listOf("alice"), d.getAccepted().map { it.username })
    }

    @Test
    fun `updateStatus promotes in place without touching the name`() = runTest {
        val d = newDomain()
        d.add("u1", "alice", FriendStatus.PENDING_SENT)

        d.updateStatus("u1", FriendStatus.ACCEPTED)

        assertEquals(FriendData("u1", "alice", FriendStatus.ACCEPTED), d.get("u1"))
    }

    @Test
    fun `remove deletes only that friend`() = runTest {
        val d = newDomain()
        d.add("u1", "alice", FriendStatus.ACCEPTED)
        d.add("u2", "bob", FriendStatus.ACCEPTED)
        d.remove("u1")

        assertNull(d.get("u1"))
        assertEquals("bob", d.get("u2")!!.username)
    }

    @Test
    fun `the link export round-trips friends with their devices and drops unknown statuses`() {
        val friends = listOf(
            LinkedFriend(FriendData("u1", "alice", FriendStatus.ACCEPTED), listOf(DeviceData("u1", "d1", "Phone"))),
            LinkedFriend(FriendData("u2", "bob", FriendStatus.PENDING_SENT), emptyList()),
        )
        assertEquals(friends, FriendExport.decode(FriendExport.encode(friends)))
        assertEquals(
            emptyList<LinkedFriend>(),
            FriendExport.decode("""[{"userId":"u3","username":"eve","status":"bogus","devices":[]}]"""),
        )
    }
}
