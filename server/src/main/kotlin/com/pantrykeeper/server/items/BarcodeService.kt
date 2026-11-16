package com.pantrykeeper.server.items

import com.pantrykeeper.server.auth.SlidingLimiter
import com.pantrykeeper.server.config.AppConfig
import java.time.Duration
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.uuid.Uuid

fun interface BarcodeFetcher {
    fun fetch(code: String): BarcodeHit?
}

data class BarcodeHit(val name: String, val defaultCategory: String?)

@kotlinx.serialization.Serializable
data class BarcodeLookup(val found: Boolean, val name: String? = null, val defaultCategory: String? = null, val reason: String? = null)

class OffBarcodeFetcher(private val config: AppConfig) : BarcodeFetcher {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = config.offTimeoutMs
            connectTimeoutMillis = config.offTimeoutMs
        }
    }

    override fun fetch(code: String): BarcodeHit? = runBlocking {
        val response = client.get("${config.offBaseUrl.trimEnd('/')}/api/v2/product/$code.json") {
            header("User-Agent", config.offUserAgent)
        }
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        if (body["status"]?.jsonPrimitive?.contentOrNull != "1") return@runBlocking null
        val product = body["product"]?.jsonObject ?: return@runBlocking null
        val name = product["product_name_zh"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: product["product_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return@runBlocking null
        BarcodeHit(name = name.take(40), defaultCategory = "food")
    }
}

class BarcodeService(private val clock: Clock, private val fetcher: BarcodeFetcher) {
    private val limiter = SlidingLimiter(clock, maxHits = 20, window = Duration.ofMinutes(1))

    fun lookup(userId: Uuid, raw: String): BarcodeLookup {
        limiter.hit(userId.toString())
        val code = raw.trim()
        if (!code.all { it.isDigit() } || code.length !in setOf(8, 12, 13, 14, 20)) {
            return BarcodeLookup(found = false, reason = "invalid_code")
        }
        if (code.length == 20) return BarcodeLookup(found = false, reason = "trace_code")
        val cached = transaction { readCache(code) }
        if (cached != null) return cached
        val hit = try {
            fetcher.fetch(code)
        } catch (_: Exception) {
            return BarcodeLookup(found = false, reason = "lookup_failed")
        } ?: return BarcodeLookup(found = false, reason = "not_found")
        transaction { writeCache(code, hit) }
        return BarcodeLookup(found = true, name = hit.name, defaultCategory = hit.defaultCategory)
    }

    private fun readCache(code: String): BarcodeLookup? = queryOne(
        "select name, default_category from barcode_cache where barcode = ? and scope = 'global' and family_id is null",
        { it.setString(1, code) },
    ) { rs ->
        update("update barcode_cache set hit_count = hit_count + 1, updated_at = ? where barcode = ? and scope = 'global' and family_id is null") {
            it.setObject(1, now())
            it.setString(2, code)
        }
        BarcodeLookup(found = true, name = rs.getString(1), defaultCategory = rs.getString(2))
    }

    private fun writeCache(code: String, hit: BarcodeHit) {
        update(
            """
            insert into barcode_cache (id, barcode, scope, family_id, name, default_category, source, hit_count, updated_at)
            values (?, ?, 'global', null, ?, ?, 'open_food_facts', 1, ?)
            on conflict (barcode, scope, family_id) do update
              set name = excluded.name, default_category = excluded.default_category, hit_count = barcode_cache.hit_count + 1, updated_at = excluded.updated_at
            """.trimIndent(),
        ) {
            it.setObject(1, Uuid.random().toJava())
            it.setString(2, code)
            it.setString(3, hit.name)
            it.setString(4, hit.defaultCategory)
            it.setObject(5, now())
        }
    }

    private fun now() = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
}

fun Uuid.toJava(): java.util.UUID = java.util.UUID.fromString(toString())

fun java.util.UUID.toKotlin(): Uuid = Uuid.parse(toString())
