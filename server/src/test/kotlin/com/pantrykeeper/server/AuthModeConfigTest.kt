package com.pantrykeeper.server

import com.pantrykeeper.server.config.AppConfig
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertFailsWith

class AuthModeConfigTest {
    @Test
    fun testModeWithEmptyCodeOrPhonesRefusesToStart() {
        assertFailsWith<IllegalStateException> {
            AppConfig.load(env(code = "", phones = ""))
        }
        assertFailsWith<IllegalStateException> {
            AppConfig.load(env(code = "12345", phones = ""))
        }
        assertFailsWith<IllegalStateException> {
            AppConfig.load(env(code = "123456", phones = ""))
        }
    }

    private fun env(code: String, phones: String): Map<String, String> {
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })
        return mapOf(
            "PANTRY_PROFILE" to "prod",
            "PANTRY_DB_URL" to "jdbc:postgresql://127.0.0.1:5432/pantry",
            "PANTRY_DB_USER" to "pantry",
            "PANTRY_DB_PASSWORD" to "",
            "PANTRY_PHONE_ENC_KEY" to key,
            "PANTRY_PHONE_HMAC_KEY" to key,
            "AUTH_MODE" to "test",
            "AUTH_TEST_CODE" to code,
            "AUTH_TEST_PHONES" to phones,
        )
    }
}
