package com.pantrykeeper.server

import com.pantrykeeper.server.config.AdjustableClock
import com.pantrykeeper.server.config.AppConfig
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.nio.file.Files
import java.time.Clock
import java.util.Base64
import java.util.UUID

/**
 * Local-only server for the Android emulator smoke. The test code and phone are
 * written under /tmp and are never printed.
 */
fun main() {
    val pg = EmbeddedPostgres.builder()
        .setLocaleConfig("locale", "C")
        .setLocaleConfig("lc-messages", "C")
        .start()
    val phone = "13" + "8" + "0".repeat(8)
    val code = ("k" + UUID.randomUUID().toString().filter { it.isDigit() } + "23456").take(6)
    val enc = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
    val mac = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
    val data = Files.createTempDirectory("pantry-smoke")
    val config = AppConfig.load(
        mapOf(
            "PANTRY_PROFILE" to "test",
            "PANTRY_DB_URL" to pg.getJdbcUrl("postgres", "postgres"),
            "PANTRY_DB_USER" to "postgres",
            "PANTRY_DB_PASSWORD" to "",
            "PANTRY_PHONE_ENC_KEY" to Base64.getEncoder().encodeToString(enc),
            "PANTRY_PHONE_HMAC_KEY" to Base64.getEncoder().encodeToString(mac),
            "AUTH_MODE" to "test",
            "AUTH_TEST_CODE" to code,
            "AUTH_TEST_PHONES" to phone,
            "PANTRY_COOKIE_SECURE" to "false",
            "PANTRY_LISTEN_HOST" to "127.0.0.1",
            "PANTRY_LISTEN_PORT" to "18280",
            "PANTRY_DATA_DIR" to data.toString(),
        ),
    )
    val env = java.io.File("/tmp/pantry-smoke")
    env.mkdirs()
    java.io.File(env, "env").writeText("PANTRY_SMOKE_PHONE=$phone\nPANTRY_SMOKE_CODE=$code\n")
    println("smoke-server-ready")
    embeddedServer(Netty, host = config.listenHost, port = config.listenPort) {
        module(config, AdjustableClock(Clock.systemUTC()))
    }.start(wait = true)
}
