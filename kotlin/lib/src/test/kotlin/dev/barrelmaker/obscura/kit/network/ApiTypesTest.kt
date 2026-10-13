package dev.barrelmaker.obscura.kit.network

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.Base64

/** The JSON shapes the server expects and returns. */
class ApiTypesTest {

    @Test
    fun `device keys nest the signed prekey and one-time prekeys`() {
        val json = DeviceKeys(
            identityKey = "idk",
            registrationId = 100,
            signedPreKey = SignedPreKeyJson(1, "spk", "sig"),
            oneTimePreKeys = listOf(OneTimePreKeyJson(2, "otk")),
        ).toJson()

        assertEquals(setOf("identityKey", "registrationId", "signedPreKey", "oneTimePreKeys"), json.keySet())
        assertEquals(100, json.getInt("registrationId"))
        assertEquals("sig", json.getJSONObject("signedPreKey").getString("signature"))
        assertEquals(2, json.getJSONArray("oneTimePreKeys").getJSONObject(0).getInt("keyId"))
        assertEquals("otk", json.getJSONArray("oneTimePreKeys").getJSONObject(0).getString("publicKey"))
    }

    @Test
    fun `an auth response has a device id only when device-scoped`() {
        val user = AuthResponse.from(JSONObject("""{"token":"t","refreshToken":"r","expiresAt":1}"""))
        val device = AuthResponse.from(JSONObject("""{"token":"t","refreshToken":"r","expiresAt":1,"deviceId":"d"}"""))
        assertNull(user.deviceId)
        assertEquals(AuthResponse("t", "r", "d"), device)
    }

    @Test
    fun `the access token's subject and expiry are read without padding`() {
        val claims = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"u-1","exp":42}""".toByteArray())
        assertEquals("u-1", Jwt.userId("h.$claims.s"))
        assertEquals(42L, Jwt.expiresAt("h.$claims.s"))
        assertNull(Jwt.expiresAt("not-a-jwt"))
    }

    @Test
    fun `Retry-After is whole seconds clamped to 1 through 10`() {
        assertEquals(1_000L, APIClient.retryAfterMs(null))
        assertEquals(1_000L, APIClient.retryAfterMs("soon"))
        assertEquals(1_000L, APIClient.retryAfterMs("0"))
        assertEquals(3_000L, APIClient.retryAfterMs(" 3 "))
        assertEquals(10_000L, APIClient.retryAfterMs("60"))
    }
}
