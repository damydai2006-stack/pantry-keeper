package com.pantrykeeper.server.config

import com.pantrykeeper.server.auth.Phones
import java.util.Base64

enum class Profile { PROD, TEST }

enum class AuthMode { DISABLED, TEST }

class AppConfig(
    val profile: Profile,
    val testClockEnabled: Boolean,
    val testToken: String,
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val phoneEncKey: ByteArray,
    val phoneHmacKey: ByteArray,
    val authMode: AuthMode,
    val testCode: String,
    val testPhones: Set<String>,
    val familyPhotoMb: Long,
    val minFreeMb: Long,
    val listenHost: String,
    val listenPort: Int,
    val cookieSecure: Boolean,
    val poolSize: Int = 5,
    val dataDir: String = "data/photos",
    val photoMaxBytes: Long = 8L * 1024L * 1024L,
    val offBaseUrl: String = "https://world.openfoodfacts.org",
    val offUserAgent: String = "pantry-keeper/0.1 (family expiry tracker)",
    val offTimeoutMs: Long = 3000,
) {
    fun withTestClock(enabled: Boolean): AppConfig = copy(testClockEnabled = enabled)

    fun copy(
        testClockEnabled: Boolean = this.testClockEnabled,
        familyPhotoMb: Long = this.familyPhotoMb,
        minFreeMb: Long = this.minFreeMb,
        dataDir: String = this.dataDir,
        photoMaxBytes: Long = this.photoMaxBytes,
    ): AppConfig = AppConfig(
        profile, testClockEnabled, testToken, dbUrl, dbUser, dbPassword, phoneEncKey, phoneHmacKey,
        authMode, testCode, testPhones, familyPhotoMb, minFreeMb, listenHost, listenPort, cookieSecure, poolSize,
        dataDir, photoMaxBytes, offBaseUrl, offUserAgent, offTimeoutMs,
    )

    override fun toString(): String =
        "AppConfig(profile=$profile, authMode=$authMode, testClock=$testClockEnabled, port=$listenPort)"

    companion object {
        fun load(env: Map<String, String>): AppConfig {
            val profileRaw = env["PANTRY_PROFILE"]?.trim()?.lowercase().orEmpty().ifEmpty { "prod" }
            val profile = when (profileRaw) {
                "prod" -> Profile.PROD
                "test" -> Profile.TEST
                else -> error("PANTRY_PROFILE must be prod or test")
            }
            val testClock = flagOn(env["PANTRY_TEST_CLOCK"])
            if (profile == Profile.PROD && testClock) {
                throw IllegalStateException("Refusing to start: test clock is enabled but PANTRY_PROFILE is prod")
            }
            val testToken = env["PANTRY_TEST_TOKEN"]?.trim().orEmpty()
            if (testClock && testToken.isEmpty()) {
                throw IllegalStateException("PANTRY_TEST_CLOCK requires PANTRY_TEST_TOKEN when the test profile is on")
            }
            val dbUrl = required(env, "PANTRY_DB_URL")
            val dbUser = required(env, "PANTRY_DB_USER")
            val dbPassword = env["PANTRY_DB_PASSWORD"].orEmpty()
            val enc = decodeKey(env["PANTRY_PHONE_ENC_KEY"], "PANTRY_PHONE_ENC_KEY")
            val hmac = decodeKey(env["PANTRY_PHONE_HMAC_KEY"], "PANTRY_PHONE_HMAC_KEY")
            val authMode = when (env["AUTH_MODE"]?.trim()?.lowercase().orEmpty()) {
                "", "disabled" -> AuthMode.DISABLED
                "test" -> AuthMode.TEST
                "sms" -> error("AUTH_MODE=sms is not implemented")
                else -> error("AUTH_MODE must be disabled or test")
            }
            val testCode = env["AUTH_TEST_CODE"]?.trim().orEmpty()
            val phones = env["AUTH_TEST_PHONES"].orEmpty().split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { raw ->
                    Phones.normalize(raw) ?: error("AUTH_TEST_PHONES contains a number that is not a mobile number")
                }
                .toSet()
            if (authMode == AuthMode.TEST) {
                require(testCode.matches(Regex("\\d{6}"))) { "AUTH_MODE=test requires a 6-digit AUTH_TEST_CODE" }
                require(phones.isNotEmpty()) { "AUTH_MODE=test requires AUTH_TEST_PHONES" }
            }
            return AppConfig(
                profile = profile,
                testClockEnabled = testClock && profile == Profile.TEST,
                testToken = testToken,
                dbUrl = dbUrl,
                dbUser = dbUser,
                dbPassword = dbPassword,
                phoneEncKey = enc,
                phoneHmacKey = hmac,
                authMode = authMode,
                testCode = testCode,
                testPhones = phones,
                familyPhotoMb = env["PANTRY_FAMILY_PHOTO_MB"]?.toLongOrNull() ?: 1024L,
                minFreeMb = env["PANTRY_MIN_FREE_MB"]?.toLongOrNull() ?: 1500L,
                listenHost = env["PANTRY_LISTEN_HOST"]?.trim().orEmpty().ifEmpty { "127.0.0.1" },
                listenPort = env["PANTRY_LISTEN_PORT"]?.toIntOrNull() ?: 18280,
                cookieSecure = !env["PANTRY_COOKIE_SECURE"].equals("false", ignoreCase = true),
                poolSize = env["PANTRY_DB_POOL"]?.toIntOrNull() ?: 5,
                dataDir = env["PANTRY_DATA_DIR"]?.trim().orEmpty().ifEmpty { "data/photos" },
                photoMaxBytes = env["PANTRY_PHOTO_MAX_BYTES"]?.toLongOrNull() ?: (8L * 1024L * 1024L),
                offBaseUrl = env["PANTRY_OFF_BASE_URL"]?.trim().orEmpty().ifEmpty { "https://world.openfoodfacts.org" },
                offUserAgent = env["PANTRY_OFF_USER_AGENT"]?.trim().orEmpty().ifEmpty { "pantry-keeper/0.1 (family expiry tracker)" },
                offTimeoutMs = env["PANTRY_OFF_TIMEOUT_MS"]?.toLongOrNull() ?: 3000L,
            )
        }

        private fun flagOn(raw: String?): Boolean =
            raw?.trim()?.lowercase() in setOf("on", "true", "yes", "1")

        private fun required(env: Map<String, String>, name: String): String =
            env[name]?.trim()?.takeIf { it.isNotEmpty() } ?: error("$name is required")

        private fun decodeKey(raw: String?, name: String): ByteArray {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) error("$name is required")
            val bytes = try {
                Base64.getDecoder().decode(text)
            } catch (_: IllegalArgumentException) {
                error("$name must be base64")
            }
            if (bytes.size != 32) error("$name must decode to 32 bytes")
            return bytes
        }
    }
}
