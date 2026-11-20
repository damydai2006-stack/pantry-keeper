package com.pantrykeeper.server

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * Builds nothing itself. Gradle task verifyShadowMigrate passes the fat jar path.
 * ServiceLoader entries are compared with the Flyway jars on this classpath, then
 * `java -jar` runs migrate so a broken merge cannot hide behind the test classpath.
 */
fun main(args: Array<String>) {
    val jar = File(args.singleOrNull() ?: error("pass the shadow jar path"))
    check(jar.isFile) { "shadow jar missing: ${jar.path}" }
    val flywayJars = System.getProperty("java.class.path")
        .split(File.pathSeparator)
        .map { File(it) }
        .filter { it.isFile && it.name.contains("flyway", ignoreCase = true) }
    check(flywayJars.isNotEmpty()) { "no flyway jars on the classpath" }
    assertFlywayServicesMerged(jar, flywayJars)
    val versions = migrateFromFatJar(jar)
    println("shadow migrate ok: ${versions.joinToString(",")}")
}

internal fun assertFlywayServicesMerged(shadow: File, flywayJars: List<File>) {
    val expected = linkedMapOf<String, LinkedHashSet<String>>()
    for (dep in flywayJars) {
        readServiceEntries(dep).forEach { (name, lines) ->
            expected.getOrPut(name) { linkedSetOf() }.addAll(lines)
        }
    }
    check(expected.isNotEmpty()) { "flyway jars have no META-INF/services entries" }
    val duplicates = duplicateServiceNames(shadow)
    check(duplicates.isEmpty()) { "fat jar still has duplicate service entries: $duplicates" }
    val actual = readServiceEntries(shadow)
    for ((name, lines) in expected) {
        val got = actual[name] ?: emptySet()
        val missing = lines - got
        check(missing.isEmpty()) { "$name missing from fat jar: $missing" }
    }
    val plugin = actual["META-INF/services/org.flywaydb.core.extensibility.Plugin"] ?: emptySet()
    check(plugin.any { it.contains("postgresql", ignoreCase = true) }) {
        "merged Flyway plugin list has no PostgreSQL entry: $plugin"
    }
    check(plugin.size > 1) { "merged Flyway plugin list collapsed to one entry: $plugin" }
    println("shadow services ok: ${expected.size} files, ${plugin.size} flyway plugins")
}

internal fun readServiceEntries(file: File): Map<String, Set<String>> {
    val out = linkedMapOf<String, LinkedHashSet<String>>()
    ZipFile(file).use { zip ->
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory || !entry.name.startsWith("META-INF/services/")) continue
            val lines = zip.getInputStream(entry).bufferedReader().use { reader ->
                reader.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            }
            out.getOrPut(entry.name) { linkedSetOf() }.addAll(lines)
        }
    }
    return out
}

internal fun duplicateServiceNames(file: File): List<String> {
    val seen = linkedMapOf<String, Int>()
    ZipFile(file).use { zip ->
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val name = entries.nextElement().name
            if (name.startsWith("META-INF/services/") && !name.endsWith("/")) {
                seen[name] = (seen[name] ?: 0) + 1
            }
        }
    }
    return seen.filterValues { it > 1 }.keys.toList()
}

private fun migrateFromFatJar(jar: File): List<String> {
    val pg = EmbeddedPostgres.builder()
        .setLocaleConfig("locale", "C")
        .setLocaleConfig("lc-messages", "C")
        .start()
    val output = Files.createTempFile("pantry-shadow-migrate", ".log")
    try {
        pg.postgresDatabase.connection.use { conn ->
            conn.createStatement().use { it.execute("CREATE DATABASE pantry") }
        }
        val url = pg.getJdbcUrl("postgres", "pantry")
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val process = ProcessBuilder(java, "-jar", jar.absolutePath, "migrate")
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .apply {
                val env = environment()
                env.remove("PANTRY_ENV_FILE")
                env["PANTRY_PROFILE"] = "prod"
                env["PANTRY_DB_URL"] = url
                env["PANTRY_DB_USER"] = "postgres"
                env["PANTRY_DB_PASSWORD"] = ""
                env["PANTRY_PHONE_ENC_KEY"] = randomKey()
                env["PANTRY_PHONE_HMAC_KEY"] = randomKey()
                env["AUTH_MODE"] = "disabled"
                env["PANTRY_LISTEN_HOST"] = "127.0.0.1"
                env["PANTRY_DATA_DIR"] = Files.createTempDirectory("pantry-shadow-photos").toString()
            }
            .start()
        if (!process.waitFor(3, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            error("fat jar migrate timed out\n${output.toFile().readText()}")
        }
        if (process.exitValue() != 0) {
            error("fat jar migrate failed (${process.exitValue()})\n${output.toFile().readText()}")
        }
        return pg.getDatabase("postgres", "pantry").connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT version FROM flyway_schema_history ORDER BY installed_rank").use { rs ->
                    val versions = mutableListOf<String>()
                    while (rs.next()) versions += rs.getString(1)
                    versions
                }
            }
        }.also { versions ->
            check(versions.containsAll(listOf("1", "2"))) { "flyway versions: $versions" }
        }
    } finally {
        pg.close()
        Files.deleteIfExists(output)
    }
}

private fun randomKey(): String {
    val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
    return Base64.getEncoder().encodeToString(bytes)
}
