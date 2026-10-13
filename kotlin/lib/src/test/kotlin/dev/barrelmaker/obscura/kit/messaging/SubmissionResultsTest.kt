package dev.barrelmaker.obscura.kit.messaging

import com.google.protobuf.ByteString
import com.sun.net.httpserver.HttpServer
import dev.barrelmaker.obscura.kit.SubmissionStatus
import dev.barrelmaker.obscura.kit.crypto.SignalStore
import dev.barrelmaker.obscura.kit.crypto.UuidCodec
import dev.barrelmaker.obscura.kit.newInMemoryDatabase
import dev.barrelmaker.obscura.kit.network.APIClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import xyz.obscura.server.contracts.ObscuraProtocol.SendMessageRequest
import xyz.obscura.server.contracts.ObscuraProtocol.SendMessageResponse
import java.net.InetSocketAddress
import java.util.UUID

class SubmissionResultsTest {
    private fun submission() = SendMessageRequest.Submission.newBuilder()
        .setSubmissionId(UuidCodec.uuidToBytes(UUID.randomUUID()))
        .setDeviceId(UuidCodec.uuidToBytes(UUID.randomUUID()))
        .setMessage(ByteString.copyFromUtf8("encrypted"))
        .build()

    @Test
    fun `maps partial rejection to exactly its submission`() {
        val a = submission()
        val b = submission()
        val response = SendMessageResponse.newBuilder().addFailedSubmissions(
            SendMessageResponse.FailedSubmission.newBuilder()
                .setSubmissionId(a.submissionId)
                .setErrorCode(SendMessageResponse.ErrorCode.ERROR_CODE_INVALID_DEVICE)
                .setErrorMessage("Device not found")
        ).build()
        val decoded = decodeSubmissionResults("user", listOf(a, b), response)
        val results = decoded.map { it.result }
        assertEquals(listOf(SubmissionStatus.REJECTED, SubmissionStatus.ACCEPTED), results.map { it.status })
        assertEquals(listOf(true, false), decoded.map { it.deviceMissing })
        assertEquals("Device not found", results[0].error)
        assertNull(results[1].error)
        assertEquals(UuidCodec.uuidString(b.deviceId), results[1].deviceId)
    }

    @Test
    fun `rejects unknown and duplicate response IDs`() {
        val a = submission()
        val failure = SendMessageResponse.FailedSubmission.newBuilder()
            .setSubmissionId(submission().submissionId).build()
        assertThrows(IllegalArgumentException::class.java) {
            decodeSubmissionResults("user", listOf(a),
                SendMessageResponse.newBuilder().addFailedSubmissions(failure).build())
        }
        val duplicate = failure.toBuilder().setSubmissionId(a.submissionId).build()
        assertThrows(IllegalArgumentException::class.java) {
            decodeSubmissionResults("user", listOf(a), SendMessageResponse.newBuilder()
                .addFailedSubmissions(duplicate).addFailedSubmissions(duplicate).build())
        }
    }

    @Test
    fun `scoped submit decodes HTTP 200 rejections and reports uncertain HTTP failures`() = runBlocking {
        val a = submission()
        val received = mutableListOf<SendMessageRequest>()
        var status = 200
        var response = SendMessageResponse.newBuilder().addFailedSubmissions(
            SendMessageResponse.FailedSubmission.newBuilder().setSubmissionId(a.submissionId)
                .setErrorMessage("Rejected")
        ).build().toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/messages") { exchange ->
            received.add(SendMessageRequest.parseFrom(exchange.requestBody.readBytes()))
            exchange.sendResponseHeaders(status, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val messenger = Messenger(SignalStore(newInMemoryDatabase()),
                APIClient("http://127.0.0.1:${server.address.port}") { "unit-test" })
            assertEquals(SubmissionStatus.REJECTED, messenger.submit("user", listOf(a)).single().result.status)
            val b = submission()
            response = byteArrayOf()
            assertEquals(SubmissionStatus.ACCEPTED, messenger.submit("other", listOf(b)).single().result.status)
            assertEquals(listOf(listOf(a), listOf(b)), received.map { it.messagesList })
            status = 503
            response = "unavailable".toByteArray()
            assertEquals(SubmissionStatus.UNKNOWN, messenger.submit("other", listOf(b)).single().result.status)
        } finally {
            server.stop(0)
        }
    }
}
