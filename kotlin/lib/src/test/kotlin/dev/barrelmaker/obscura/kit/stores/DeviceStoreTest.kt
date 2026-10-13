package dev.barrelmaker.obscura.kit.stores

import dev.barrelmaker.obscura.kit.newInMemoryDatabase
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The device directory: the only record of which devices each user has. */
class DeviceStoreTest {

    private val db = newInMemoryDatabase()
    private val devices = DeviceStore(db)
    private val friends = FriendStore(db)

    private suspend fun ids(userId: String) = devices.devicesOf(userId).map { it.deviceId }

    @Test
    fun `identity round-trips and starts empty`() = runTest {
        assertNull(devices.getIdentity())
        devices.storeIdentity(DeviceIdentityData("device-1"))
        assertEquals(DeviceIdentityData("device-1"), devices.getIdentity())
    }

    @Test
    fun `devices are listed per user`() = runTest {
        devices.add(DeviceData("me", "d1", "Pixel"))
        devices.add(DeviceData("peer", "d2"))

        assertEquals(listOf(DeviceData("me", "d1", "Pixel")), devices.devicesOf("me"))
        assertEquals(listOf("d2"), ids("peer"))
    }

    @Test
    fun `replace swaps a user's whole list and leaves other users alone`() = runTest {
        devices.add(DeviceData("me", "old"))
        devices.add(DeviceData("peer", "p1"))

        devices.replace("me", listOf(DeviceData("me", "a", "A"), DeviceData("me", "b", "B")))

        assertEquals(listOf("a", "b"), ids("me"))
        assertEquals(listOf("p1"), ids("peer"))
    }

    @Test
    fun `a device id claimed for one user cannot be taken from another`() = runTest {
        devices.add(DeviceData("victim", "d1"))
        friends.add("mallory", "mallory", FriendStatus.ACCEPTED)

        devices.applyAnnounce("mallory", listOf(DeviceData("mallory", "d1")), 1_000)

        assertEquals(listOf("d1"), ids("victim"))
        assertEquals(listOf("d1"), ids("mallory"))
    }

    @Test
    fun `a server refresh keeps known names and drops devices the server no longer lists`() = runTest {
        devices.replace("peer", listOf(DeviceData("peer", "keep", "Phone"), DeviceData("peer", "gone", "Tablet")))

        devices.replaceFromServer("peer", listOf("keep", "new"))

        assertEquals(listOf(DeviceData("peer", "keep", "Phone"), DeviceData("peer", "new", "")), devices.devicesOf("peer"))
    }

    @Test
    fun `announcements apply only to friends and only when newer`() = runTest {
        assertFalse(devices.applyAnnounce("stranger", listOf(DeviceData("stranger", "d")), 1_000))
        assertEquals(emptyList<String>(), ids("stranger"))

        friends.add("peer", "alice", FriendStatus.ACCEPTED)
        assertTrue(devices.applyAnnounce("peer", listOf(DeviceData("peer", "v2")), 2_000))
        assertFalse(devices.applyAnnounce("peer", listOf(DeviceData("peer", "v1")), 1_000))
        assertFalse(devices.applyAnnounce("peer", listOf(DeviceData("peer", "v1")), 2_000))

        assertEquals(listOf("v2"), ids("peer"))
    }

    @Test
    fun `a server refresh outranks announcements sent before it`() = runTest {
        friends.add("peer", "alice", FriendStatus.ACCEPTED)
        devices.replaceFromServer("peer", listOf("current"))

        assertFalse(devices.applyAnnounce("peer", listOf(DeviceData("peer", "stale")), System.currentTimeMillis() - 1_000))
        assertEquals(listOf("current"), ids("peer"))
    }

    @Test
    fun `clear removes identity and every device`() = runTest {
        devices.storeIdentity(DeviceIdentityData("device-1"))
        devices.add(DeviceData("me", "device-1"))

        devices.clear()

        assertNull(devices.getIdentity())
        assertEquals(emptyList<String>(), ids("me"))
    }
}
