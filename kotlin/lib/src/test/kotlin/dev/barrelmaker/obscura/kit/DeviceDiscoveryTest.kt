package dev.barrelmaker.obscura.kit

import dev.barrelmaker.obscura.kit.managers.FakeObscuraServer
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.WebSocketListener
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DeviceDiscoveryTest {
    @Test fun `own directory persistence failure is rejected without invalidating accepted peers`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            FakeObscuraServer().use { peers ->
                val peer = peers.register("019f025a-0000-7de6-84b6-000000000002")
                val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(
                    app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY
                )
                dev.barrelmaker.obscura.kit.db.ObscuraDatabase.Schema.create(driver)
                val c = ObscuraClient(ObscuraConfig(server.url("/").toString(), "test"), driver, storedSession())
                c.signalStore.generateIdentity()
                c.devices.storeIdentity(SELF_DEVICE_ID)
                assertTrue(c.auth.restore())
                driver.execute(null,
                    "CREATE TRIGGER fail_own_directory BEFORE INSERT ON Device WHEN NEW.user_id = '$SELF_USER_ID' BEGIN SELECT RAISE(FAIL, 'directory unavailable'); END", 0)
                server.enqueue(MockResponse.Builder().body("[${peer.consumeBundle()}]").build())
                server.enqueue(MockResponse.Builder().body("").build())
                server.enqueue(MockResponse.Builder().body("""{"devices":[{"deviceId":"$SELF_DEVICE_ID"}]}""").build())

                val report = c.send(listOf(peer.userId), "m", "e", 1, byteArrayOf(1), emptySet())

                assertEquals(SubmissionStatus.ACCEPTED, report.recipients.single().status)
                assertEquals(SubmissionStatus.REJECTED, report.selfSync.single().status)
                assertEquals(SELF_USER_ID, report.selfSync.single().userId)
                assertNull(report.selfSync.single().deviceId)
                assertTrue(report.selfSync.single().error!!.contains("directory unavailable"))
                assertEquals(3, server.requestCount)
                c.auth.end()
                driver.close()
            }
        }
    }

    @Test fun `own metadata failure leaves named recipient acceptance intact and reports unknown self sync`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            FakeObscuraServer().use { peers ->
                val peer = peers.register("019f025a-0000-7de6-84b6-000000000002")
                val c = sessionClient(server.url("/").toString().trimEnd('/'), storedSession())
                assertTrue(c.auth.restore())
                server.enqueue(MockResponse.Builder().body("[${peer.consumeBundle()}]").build())
                server.enqueue(MockResponse.Builder().body("").build())
                server.enqueue(MockResponse.Builder().code(500).body("directory unavailable").build())

                val report = c.send(listOf(peer.userId), "m", "e", 1, byteArrayOf(1), emptySet())

                assertEquals(peer.deviceId, report.recipients.single().deviceId)
                assertEquals(SubmissionStatus.ACCEPTED, report.recipients.single().status)
                assertEquals(SELF_USER_ID, report.selfSync.single().userId)
                assertNull(report.selfSync.single().deviceId)
                assertEquals(SubmissionStatus.UNKNOWN, report.selfSync.single().status)
                assertEquals(listOf("/v1/users/${peer.userId}", "/v1/messages", "/v1/devices"),
                    List(server.requestCount) { server.takeRequest().url.encodedPath })

                server.enqueue(MockResponse.Builder().code(500).build())
                val retry = c.send(listOf(peer.userId), "m", "e", 1, byteArrayOf(1), setOf(peer.deviceId))
                assertEquals(SubmissionStatus.ACCEPTED, retry.recipients.single().status)
                assertEquals(SubmissionStatus.UNKNOWN, retry.selfSync.single().status)
                assertEquals(4, server.requestCount)
                c.auth.end()
                Unit
            }
        }
    }

    @Test fun `an old connected socket discovers a new own device on next send without repeated bundles`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val c = sessionClient(server.url("/").toString().trimEnd('/'), storedSession())
            assertTrue(c.auth.restore())
            fun directory(vararg ids: String) = MockResponse.Builder().body(
                """{"devices":[${ids.joinToString { """{"deviceId":"$it","name":"test","createdAt":"2026-10-13"}""" }}]}"""
            ).build()
            server.enqueue(directory(SELF_DEVICE_ID))
            server.enqueue(MockResponse.Builder().body("""{"ticket":"t"}""").build())
            server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }
            }).build())
            c.connect()
            server.enqueue(directory(SELF_DEVICE_ID))
            val initial = c.send(emptyList(), "m", "e1", 1, byteArrayOf(1), emptySet())
            assertTrue(initial.selfSync.isEmpty())

            FakeObscuraServer().use { peers ->
                val other = peers.register(SELF_USER_ID)
                server.enqueue(directory(SELF_DEVICE_ID, other.deviceId))
                server.enqueue(MockResponse.Builder().body("[${other.consumeBundle()}]").build())
                server.enqueue(MockResponse.Builder().body("").build())
                val report = c.send(emptyList(), "m", "e2", 2, byteArrayOf(2), emptySet())
                assertEquals(listOf(other.deviceId), report.selfSync.map { it.deviceId })
                assertEquals(SubmissionStatus.ACCEPTED, report.selfSync.single().status)
                val preKeys = other.remainingPreKeys

                server.enqueue(directory(SELF_DEVICE_ID, other.deviceId))
                server.enqueue(MockResponse.Builder().body("").build())
                c.send(emptyList(), "m", "e3", 3, byteArrayOf(3), emptySet())
                assertEquals(preKeys, other.remainingPreKeys)

                server.enqueue(directory(SELF_DEVICE_ID, other.deviceId))
                val accepted = c.send(emptyList(), "m", "e3", 3, byteArrayOf(3), setOf(other.deviceId))
                assertEquals(SubmissionStatus.ACCEPTED, accepted.selfSync.single().status)
                val paths = List(server.requestCount) { server.takeRequest().url.encodedPath }
                assertEquals(5, paths.count { it == "/v1/devices" })
                assertEquals(1, paths.count { it == "/v1/users/$SELF_USER_ID" })
                assertEquals(2, paths.count { it == "/v1/messages" })
            }
            c.disconnect()
            c.auth.end()
            Unit
        }
    }

    @Test fun `own metadata does not consume bundles or establish sessions`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val c = sessionClient(server.url("/").toString().trimEnd('/'), storedSession())
            assertTrue(c.auth.restore())
            val other = "019f025a-0000-7de6-84b6-0000000000d2"
            server.enqueue(MockResponse.Builder().body(
                """{"devices":[{"deviceId":"$SELF_DEVICE_ID"},{"deviceId":"$other"}]}"""
            ).build())
            server.enqueue(MockResponse.Builder().code(500).build())
            assertTrue(runCatching { c.connect() }.isFailure)
            assertEquals(listOf(SELF_DEVICE_ID, other).sorted(), c.devices.devicesOf(SELF_USER_ID))
            assertEquals(0L, c.db.signalKeyQueries.countPreKeys().executeAsOne())
            assertFalse(c.signalStore.containsSession(dev.barrelmaker.obscura.kit.messaging.Messenger.addressFor(other)))
            assertEquals(listOf("/v1/devices", "/v1/gateway/ticket"),
                List(server.requestCount) { server.takeRequest().url.encodedPath })
            c.disconnect()
            c.auth.end()
            Unit
        }
    }
}
