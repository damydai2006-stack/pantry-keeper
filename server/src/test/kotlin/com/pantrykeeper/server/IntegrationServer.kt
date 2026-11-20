package com.pantrykeeper.server

import com.pantrykeeper.server.config.AppConfig
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.time.Clock
import java.time.ZoneOffset
import java.util.Base64

/**
 * Loopback server for the web integration tests. Two synthetic allowlist
 * entries and the verifier are written under /tmp and are never printed.
 * This main is test-source only; it is not in the server jar.
 */
fun main() {
    val port = 18281
    val host = "127.0.0.1"
    val pg = EmbeddedPostgres.builder()
        .setLocaleConfig("locale", "C")
        .setLocaleConfig("lc-messages", "C")
        .start()
    val phoneA = syntheticPhone(1)
    val phoneB = syntheticPhone(2)
    val code = sixDigitCode()
    val enc = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
    val mac = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
    val data = Files.createTempDirectory("pantry-it")
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
            "AUTH_TEST_PHONES" to "$phoneA,$phoneB",
            "PANTRY_COOKIE_SECURE" to "false",
            "PANTRY_LISTEN_HOST" to host,
            "PANTRY_LISTEN_PORT" to port.toString(),
            "PANTRY_DATA_DIR" to data.toString(),
        ),
    )
    val dir = java.io.File("/tmp/pantry-it")
    dir.mkdirs()
    val env = java.io.File(dir, "env")
    env.writeText(
        "PANTRY_IT_BASE=http://$host:$port\n" +
            "PANTRY_IT_PHONE_A=$phoneA\n" +
            "PANTRY_IT_PHONE_B=$phoneB\n" +
            "PANTRY_IT_CODE=$code\n",
    )
    Files.setPosixFilePermissions(
        env.toPath(),
        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
    )
    println("it-server-ready")
    embeddedServer(Netty, host = config.listenHost, port = config.listenPort) {
        module(config, Clock.system(ZoneOffset.UTC))
    }.start(wait = true)
}

private fun syntheticPhone(suffix: Int): String = "13" + suffix.toString().padStart(9, '0')
