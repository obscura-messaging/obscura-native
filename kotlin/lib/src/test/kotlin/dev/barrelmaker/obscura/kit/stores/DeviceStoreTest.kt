package dev.barrelmaker.obscura.kit.stores

import dev.barrelmaker.obscura.kit.newInMemoryDatabase
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DeviceStoreTest {
    private val devices = DeviceStore(newInMemoryDatabase())

    @Test
    fun `identity round-trips and starts empty`() = runTest {
        assertNull(devices.getIdentity())
        devices.storeIdentity("device-1")
        assertEquals("device-1", devices.getIdentity())
    }

    @Test
    fun `devices are listed per user and a repeated id is stored once`() = runTest {
        devices.replace("me", listOf("d1", "d1"))
        devices.replace("peer", listOf("d2"))

        assertEquals(listOf("d1"), devices.devicesOf("me"))
        assertEquals(listOf("d2"), devices.devicesOf("peer"))
    }

    @Test
    fun `replace swaps one user's list and leaves other users alone`() = runTest {
        devices.replace("me", listOf("old"))
        devices.replace("peer", listOf("p1"))

        devices.replace("me", listOf("b", "a"))

        assertEquals(listOf("a", "b"), devices.devicesOf("me"))
        assertEquals(listOf("p1"), devices.devicesOf("peer"))
    }

    @Test
    fun `the same device id under two users is two entries`() = runTest {
        devices.replace("victim", listOf("d1"))
        devices.replace("mallory", listOf("d1"))
        devices.replace("mallory", emptyList())

        assertEquals(listOf("d1"), devices.devicesOf("victim"))
        assertEquals(emptyList<String>(), devices.devicesOf("mallory"))
    }
}
