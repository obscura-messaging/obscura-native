package scenarios

import com.google.protobuf.ByteString
import dev.barrelmaker.obscura.kit.TypingState
import dev.barrelmaker.obscura.kit.wire.WireCodec
import obscura.client.v1.Client
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * Vector-driven client-wire conformance, consuming the shared
 * `protocol/conformance/wire.json`. Both platforms run the
 * same file.
 *
 * Pins the signal enum <-> app-facing-form mapping and that a AppEntry
 * round-trips through the wire by VALUE.
 * Byte-canonicity is intentionally NOT asserted.
 */
class WireConformanceTest {

    @TestFactory
    fun `wire conformance`(): List<DynamicTest> {
        val v = loadVectors("wire.json")
        val tests = mutableListOf<DynamicTest>()

        forEach(v.getJSONArray("messageTypes")) { c ->
            val wire = c.getString("wire"); val app = c.getString("app")
            tests.add(dyn("messageType $wire -> \"$app\"") {
                val case = Client.ClientMessage.PayloadCase.valueOf(wire.uppercase())
                assertEquals(app, WireCodec.decodeType(case))
            })
        }

        forEach(v.getJSONArray("typingStates")) { c ->
            val wire = c.getString("wire"); val app = c.getString("app")
            tests.add(dyn("typingState $wire <-> \"$app\"") {
                val wireState = Client.TypingState.valueOf(wire)
                val appState = TypingState.valueOf(app.uppercase())
                assertEquals(appState, WireCodec.decodeTypingState(wireState), "decode $wire")
                assertEquals(wireState, WireCodec.encodeTypingState(appState), "encode $app")
            })
        }

        forEach(v.getJSONArray("roundTrip")) { c ->
            tests.add(dyn(c.getString("name")) { roundTrip(c.getJSONObject("appEntry")) })
        }

        return tests
    }

    /** Serialize to protobuf bytes and parse back — a true wire round-trip. */
    private fun roundTrip(ms: JSONObject) {
        val model = ms.getString("model")
        val id = ms.getString("id")
        val ts = ms.getLong("timestamp")
        val dataMap = ms.getJSONObject("data").toMap()

        val proto = Client.AppEntry.newBuilder()
            .setModel(model)
            .setId(id)
            .setTimestamp(ts)
            .setData(ByteString.copyFrom(JSONObject(dataMap).toString().toByteArray()))
            .build()

        val decoded = Client.AppEntry.parseFrom(proto.toByteArray())

        assertEquals(model, decoded.model, "model")
        assertEquals(id, decoded.id, "id")
        assertEquals(ts, decoded.timestamp, "timestamp")
        // data round-trips by VALUE (parsed map), not bytes — key order is irrelevant.
        assertEquals(dataMap, JSONObject(String(decoded.data.toByteArray())).toMap(), "data value")
    }

    private fun dyn(name: String, exec: () -> Unit) = DynamicTest.dynamicTest(name, exec)

    private inline fun forEach(arr: org.json.JSONArray, body: (JSONObject) -> Unit) {
        for (i in 0 until arr.length()) body(arr.getJSONObject(i))
    }

    private fun loadVectors(name: String): JSONObject {
        val candidates = listOf(
            "../../protocol/conformance/$name",
            "../protocol/conformance/$name",
            "protocol/conformance/$name",
        )
        val file = candidates.map(::File).firstOrNull { it.exists() }
            ?: error(
                "conformance vector '$name' not found (looked in: ${candidates.joinToString()}). " +
                    "Is protocol/conformance present in the repository checkout?",
            )
        return JSONObject(file.readText())
    }
}
