package com.pantrykeeper.server

import com.pantrykeeper.server.config.AdjustableClock
import com.pantrykeeper.server.config.AppConfig
import com.pantrykeeper.server.db.DatabaseFactory
import com.pantrykeeper.server.family.FamilyAccess
import com.pantrykeeper.server.family.ResourceDecision
import com.pantrykeeper.server.http.ErrorBody
import com.pantrykeeper.server.http.appJson
import com.pantrykeeper.server.photo.PhotoQuota
import com.pantrykeeper.server.photo.PhotoReject
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import com.pantrykeeper.server.family.FamilyView
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ServerFlowTest {
    @Test
    fun authSessionAndFamilyRules() = withApp { client ->
        val a = phone(1)
        val b = phone(2)
        val outsider = phone(9)
        requestCode(client, a)
        repeat(4) {
            val wrong = client.post("/api/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest(a, "nope", "app", null))
            }
            assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        }
        val fifth = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(a, "nope", "app", null))
        }
        assertEquals("code_invalidated", appJson.decodeFromString(ErrorBody.serializer(), fifth.bodyAsText()).error)
        val locked = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(a, Support.testCode, "app", null))
        }
        assertEquals("code_invalidated", appJson.decodeFromString(ErrorBody.serializer(), locked.bodyAsText()).error)

        requestCode(client, a)
        val app = login(client, a, "app")
        requestCode(client, a)
        val web = login(client, a, "web")
        assertTrue(app.token != null && web.token == null)
        assertEquals(HttpStatusCode.OK, authedGet(client, "/api/v1/me", app).status)
        assertEquals(HttpStatusCode.OK, authedGet(client, "/api/v1/me", web).status)
        val logout = client.post("/api/v1/auth/logout") { header(HttpHeaders.Authorization, "Bearer ${app.token}") }
        assertEquals(HttpStatusCode.OK, logout.status)
        assertEquals(HttpStatusCode.Unauthorized, authedGet(client, "/api/v1/me", app).status)
        assertEquals(HttpStatusCode.OK, authedGet(client, "/api/v1/me", web).status)

        requestCode(client, a)
        val again = login(client, a, "app")
        val tLogin = Support.clock.offsetSeconds()
        Support.clock.setOffsetSeconds(tLogin + 29L * 86400)
        assertEquals(HttpStatusCode.OK, authedGet(client, "/api/v1/me", again).status)
        Support.clock.setOffsetSeconds(tLogin + 30L * 86400 + 5)
        assertEquals(HttpStatusCode.Unauthorized, authedGet(client, "/api/v1/me", web).status)
        assertEquals(HttpStatusCode.OK, authedGet(client, "/api/v1/me", again).status)
        Support.clock.setOffsetSeconds(tLogin + 30L * 86400 + 5 + 30L * 86400 + 5)
        assertEquals(HttpStatusCode.Unauthorized, authedGet(client, "/api/v1/me", again).status)

        Support.clock.setOffsetSeconds(0)
        Support.truncate()
        requestCode(client, a)
        val admin = login(client, a, "app")
        val created = client.post("/api/v1/families") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateFamilyRequest("我家的米"))
        }
        assertEquals(HttpStatusCode.OK, created.status)
        val family = created.body<FamilyView>()
        assertEquals("admin", family.role)
        assertEquals(6, family.inviteCode?.length)

        requestCode(client, b)
        val member = login(client, b, "app")
        val joined = client.post("/api/v1/families/join") {
            header(HttpHeaders.Authorization, "Bearer ${member.token}")
            contentType(ContentType.Application.Json)
            setBody(JoinRequest(family.inviteCode!!))
        }
        assertEquals(HttpStatusCode.OK, joined.status)
        val seen = joined.body<FamilyView>()
        assertEquals(family.inviteCode, seen.inviteCode)
        assertEquals("member", seen.role)

        val renameDenied = client.patch("/api/v1/families/current") {
            header(HttpHeaders.Authorization, "Bearer ${member.token}")
            contentType(ContentType.Application.Json)
            setBody(RenameRequest("被改掉了"))
        }
        assertEquals(HttpStatusCode.Forbidden, renameDenied.status)
        assertEquals("admin_only", appJson.decodeFromString(ErrorBody.serializer(), renameDenied.bodyAsText()).error)
        val still = authedGet(client, "/api/v1/families/current", admin).body<FamilyView>()
        assertEquals("我家的米", still.name)

        val resetDenied = client.post("/api/v1/families/current/invite/reset") {
            header(HttpHeaders.Authorization, "Bearer ${member.token}")
        }
        assertEquals(HttpStatusCode.Forbidden, resetDenied.status)

        val leaveBlocked = client.post("/api/v1/families/leave") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
        }
        assertEquals(HttpStatusCode.Conflict, leaveBlocked.status)
        assertEquals("admin_must_transfer", appJson.decodeFromString(ErrorBody.serializer(), leaveBlocked.bodyAsText()).error)

        val transferred = client.post("/api/v1/families/current/admin") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            contentType(ContentType.Application.Json)
            setBody(TransferRequest(member.userId))
        }
        assertEquals(HttpStatusCode.OK, transferred.status)
        assertEquals("member", transferred.body<FamilyView>().role)
        val adminLeft = client.post("/api/v1/families/leave") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
        }
        assertEquals(HttpStatusCode.OK, adminLeft.status)
        assertEquals(HttpStatusCode.Forbidden, authedGet(client, "/api/v1/families/current", admin).status)

        val oldCode = family.inviteCode ?: error("missing invite code")
        requestCode(client, a)
        val returned = login(client, a, "app")
        val rejoined = client.post("/api/v1/families/join") {
            header(HttpHeaders.Authorization, "Bearer ${returned.token}")
            contentType(ContentType.Application.Json)
            setBody(JoinRequest(oldCode))
        }
        assertEquals(HttpStatusCode.OK, rejoined.status)

        val removed = client.delete("/api/v1/families/current/members/${returned.userId}") {
            header(HttpHeaders.Authorization, "Bearer ${member.token}")
        }
        assertEquals(HttpStatusCode.OK, removed.status)
        val afterRemove = authedGet(client, "/api/v1/families/current", returned)
        assertEquals(HttpStatusCode.Forbidden, afterRemove.status)
        assertFalse(afterRemove.bodyAsText().contains("我家的米"))

        client.post("/api/v1/families/leave") { header(HttpHeaders.Authorization, "Bearer ${member.token}") }
        val leftAt = Support.clock.offsetSeconds()
        val ghost = client.post("/api/v1/families/join") {
            header(HttpHeaders.Authorization, "Bearer ${returned.token}")
            contentType(ContentType.Application.Json)
            setBody(JoinRequest(oldCode))
        }
        assertEquals(HttpStatusCode.NotFound, ghost.status)
        assertEquals("invite_invalid", appJson.decodeFromString(ErrorBody.serializer(), ghost.bodyAsText()).error)
        assertFalse(ghost.bodyAsText().contains("我家的米"))

        Support.clock.setOffsetSeconds(leftAt + 29L * 86400)
        val early = client.post("/api/v1/test/purge-now") { header("X-Pantry-Test-Token", Support.testToken) }
        assertEquals(0, early.body<PurgeResponse>().purgedFamilies)
        Support.clock.setOffsetSeconds(leftAt + 30L * 86400 + 5)
        val purged = client.post("/api/v1/test/purge-now") { header("X-Pantry-Test-Token", Support.testToken) }
        assertEquals(1, purged.body<PurgeResponse>().purgedFamilies)

        requestCode(client, outsider)
        val denied = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(outsider, Support.testCode, "app", null))
        }
        assertEquals(HttpStatusCode.Unauthorized, denied.status)
    }

    @Test
    fun inviteExpiresAfterSevenDays() = withApp { client ->
        val a = phone(3)
        val b = phone(4)
        requestCode(client, a)
        val admin = login(client, a, "app")
        val created = client.post("/api/v1/families") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            contentType(ContentType.Application.Json)
            setBody(CreateFamilyRequest("七日家庭"))
        }
        val code = created.body<FamilyView>().inviteCode!!
        val createdAt = Support.clock.offsetSeconds()
        requestCode(client, b)
        val member = login(client, b, "app")
        Support.clock.setOffsetSeconds(createdAt + 7L * 86400 - 1)
        val ok = client.post("/api/v1/families/join") {
            header(HttpHeaders.Authorization, "Bearer ${member.token}")
            contentType(ContentType.Application.Json)
            setBody(JoinRequest(code))
        }
        assertEquals(HttpStatusCode.OK, ok.status)
        client.post("/api/v1/families/leave") { header(HttpHeaders.Authorization, "Bearer ${member.token}") }
        Support.clock.setOffsetSeconds(createdAt + 7L * 86400)
        val expired = client.post("/api/v1/families/join") {
            header(HttpHeaders.Authorization, "Bearer ${member.token}")
            contentType(ContentType.Application.Json)
            setBody(JoinRequest(code))
        }
        assertEquals(HttpStatusCode.NotFound, expired.status)
    }

    @Test
    fun webCookieIsIndependentAndRequiresCsrf() = withApp { client ->
        val a = phone(5)
        requestCode(client, a)
        val web = login(client, a, "web")
        val setCookie = web.setCookie
        assertTrue(setCookie.contains("HttpOnly"))
        assertTrue(setCookie.contains("SameSite=Strict"))
        assertTrue(setCookie.contains("Secure"))
        val missing = client.post("/api/v1/families") {
            header(HttpHeaders.Cookie, "pantry_session=${web.cookie}")
            contentType(ContentType.Application.Json)
            setBody(CreateFamilyRequest("网页家庭"))
        }
        assertEquals(HttpStatusCode.Forbidden, missing.status)
        val created = client.post("/api/v1/families") {
            header(HttpHeaders.Cookie, "pantry_session=${web.cookie}")
            header("X-Pantry-Request", "1")
            contentType(ContentType.Application.Json)
            setBody(CreateFamilyRequest("网页家庭"))
        }
        assertEquals(HttpStatusCode.OK, created.status)
        val open = client.get("/api/v1/me")
        assertEquals(HttpStatusCode.Unauthorized, open.status)
    }

    @Test
    fun testClockRouteAndProductionRefusal() = withApp { client ->
        val moved = client.post("/api/v1/test/clock") {
            header("X-Pantry-Test-Token", Support.testToken)
            contentType(ContentType.Application.Json)
            setBody(ClockRequest(90))
        }
        assertEquals(HttpStatusCode.OK, moved.status)
        assertEquals(90L, moved.body<ClockResponse>().offsetSeconds)
        val denied = client.post("/api/v1/test/clock") {
            contentType(ContentType.Application.Json)
            setBody(ClockRequest(1))
        }
        assertEquals(HttpStatusCode.Unauthorized, denied.status)
        val hidden = AppConfig.load(Support.env(testClock = false))
        testApplication {
            application { module(hidden.withTestClock(false), Support.clock) }
            val other = jsonClient()
            val missing = other.post("/api/v1/test/clock") {
                header("X-Pantry-Test-Token", Support.testToken)
                contentType(ContentType.Application.Json)
                setBody(ClockRequest(1))
            }
            assertEquals(HttpStatusCode.NotFound, missing.status)
        }
    }
}

class PureRuleTest {
    @Test
    fun productionRefusesEnabledTestClock() {
        val error = assertFailsWith<IllegalStateException> {
            AppConfig.load(mapOf("PANTRY_PROFILE" to "prod", "PANTRY_TEST_CLOCK" to "on"))
        }
        assertTrue(error.message!!.contains("test clock is enabled"))
    }

    @Test
    fun photoQuotaDefaults() {
        val quota = PhotoQuota(1024, 1500)
        val gb = 1024L * 1024L * 1024L
        val freeOk = 1500L * 1024L * 1024L
        assertEquals(PhotoReject.NONE, quota.evaluate(0, 1, freeOk).reject)
        assertEquals(507, quota.evaluate(0, 1, freeOk - 1).httpStatus)
        assertEquals(413, quota.evaluate(gb - 10, 11, freeOk).httpStatus)
        assertTrue(quota.evaluate(gb - 10, 10, freeOk).allowed)
    }

    @Test
    fun familyAccessHidesForeignIds() {
        val a = Uuid.random()
        val b = Uuid.random()
        assertEquals(ResourceDecision.ALLOW, FamilyAccess.decide(a, a))
        assertEquals(ResourceDecision.NOT_FOUND, FamilyAccess.decide(a, b))
        assertEquals(ResourceDecision.NO_FAMILY, FamilyAccess.decide(null, a))
        assertEquals(ResourceDecision.NOT_FOUND, FamilyAccess.decideAll(a, listOf(a, b)))
        assertEquals(ResourceDecision.ALLOW, FamilyAccess.decideAll(a, listOf(a, a)))
    }

    @Test
    fun installSecretsStayOffTheCommandLine() {
        val pwsh = listOf("pwsh", "/tmp/pwsh/pwsh").firstOrNull { candidate ->
            if (candidate.startsWith("/")) java.io.File(candidate).canExecute() else runCatching {
                ProcessBuilder("which", candidate).start().waitFor() == 0
            }.getOrDefault(false)
        } ?: error("pwsh is required to check the installer secret plan")
        val script = java.io.File(System.getProperty("repo.root"), "deploy/windows/install-secrets.test.ps1")
        val proc = ProcessBuilder(pwsh, "-NoProfile", "-File", script.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        assertEquals(0, code, output)
        assertTrue(output.contains("install-secrets.test.ps1 ok"))
    }

    @Test
    fun deployScriptsStayInsidePantry() {
        val dir = java.io.File(System.getProperty("repo.root"), "deploy/windows")
        val scripts = dir.listFiles { f -> f.extension == "ps1" }!!.toList()
        assertTrue(scripts.size >= 6)
        for (file in scripts) {
            val text = file.readText()
            assertFalse(text.contains("postgresql.conf"), file.name)
            assertFalse(Regex("(?i)alter\\s+role\\s+ptt").containsMatchIn(text), file.name)
            assertFalse(Regex("(?i)restart-service\\s+-name\\s+['\"]?(postgres|ptt)").containsMatchIn(text), file.name)
            assertFalse(Regex("(?i)net\\s+stop\\s+postgres").containsMatchIn(text), file.name)
            text.lineSequence().forEach { line ->
                if ("pg_reload_conf" in line) {
                    assertTrue(line.contains("Write-Host"), "${file.name} executes pg_reload_conf")
                }
            }
        }
        assertTrue(java.io.File(dir, "common.ps1").readText().contains("Assert-PantryScope"))
    }

    @Test
    fun schemaAllowsHistoryDeleteAndLengths() {
        Support.ensure()
        Support.truncate()
        DatabaseFactory.dataSource.connection.use { conn ->
            conn.autoCommit = false
            val user = UUID.randomUUID()
            val family = UUID.randomUUID()
            val item = UUID.randomUUID()
            conn.prepareStatement(
                "insert into users (id, phone_enc, phone_hmac, display_name, created_at, updated_at) values (?, ?, ?, '用户', now(), now())",
            ).use {
                it.setObject(1, user)
                it.setBytes(2, byteArrayOf(1))
                it.setBytes(3, byteArrayOf(2))
                it.executeUpdate()
            }
            conn.prepareStatement(
                "insert into families (id, name, admin_user_id, created_at, updated_at, rev) values (?, '家庭', ?, now(), now(), 1)",
            ).use {
                it.setObject(1, family)
                it.setObject(2, user)
                it.executeUpdate()
            }
            conn.prepareStatement(
                """
                insert into items (
                  id, family_id, name, expiry_date, expiry_basis, status, created_at, updated_at, rev
                ) values (?, ?, '历史奶', '2026-01-01', 'manual_date', 'used_up', now(), now(), nextval('item_rev_seq'))
                """.trimIndent(),
            ).use {
                it.setObject(1, item)
                it.setObject(2, family)
                it.executeUpdate()
            }
            conn.prepareStatement("delete from items where id = ? and status = 'used_up'").use {
                it.setObject(1, item)
                assertEquals(1, it.executeUpdate())
            }
            assertFailsWith<java.sql.SQLException> {
                conn.prepareStatement("insert into families (id, name, created_at, updated_at, rev) values (?, '家', now(), now(), 1)")
                    .use {
                        it.setObject(1, UUID.randomUUID())
                        it.executeUpdate()
                    }
            }
            conn.rollback()
        }
    }
}

private fun phone(n: Int): String = "13" + n.toString().padStart(9, '0')

private data class Session(val userId: String, val token: String?, val cookie: String?, val setCookie: String)

private suspend fun requestCode(client: HttpClient, phone: String) {
    Support.clock.setOffsetSeconds(Support.clock.offsetSeconds() + 120)
    val res = client.post("/api/v1/auth/code") {
        contentType(ContentType.Application.Json)
        setBody(CodeRequest(phone))
    }
    assertEquals(HttpStatusCode.OK, res.status)
}

private suspend fun login(client: HttpClient, phone: String, kind: String): Session {
    val res = client.post("/api/v1/auth/login") {
        contentType(ContentType.Application.Json)
        setBody(LoginRequest(phone, Support.testCode, kind, null))
    }
    assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
    val body = res.body<LoginResponse>()
    val setCookie = res.headers.getAll("Set-Cookie")?.joinToString(";") ?: ""
    val cookie = Regex("pantry_session=([^;]+)").find(setCookie)?.groupValues?.get(1)
    return Session(body.userId, body.token, cookie, setCookie)
}

private suspend fun authedGet(client: HttpClient, path: String, session: Session) =
    client.get(path) {
        if (session.token != null) header(HttpHeaders.Authorization, "Bearer ${session.token}")
        else header(HttpHeaders.Cookie, "pantry_session=${session.cookie}")
    }

private fun io.ktor.server.testing.ApplicationTestBuilder.jsonClient() = createClient {
    install(ContentNegotiation) { json(appJson) }
}

private fun withApp(block: suspend io.ktor.server.testing.ApplicationTestBuilder.(HttpClient) -> Unit) {
    Support.ensure()
    Support.truncate()
    Support.clock.setOffsetSeconds(0)
    testApplication {
        application { module(Support.config, Support.clock) }
        block(jsonClient())
    }
}

private object Support {
    lateinit var pg: EmbeddedPostgres
    lateinit var config: AppConfig
    lateinit var clock: AdjustableClock
    lateinit var testCode: String
    lateinit var testToken: String
    private val base = Instant.parse("2026-11-15T00:00:00Z")

    fun ensure() {
        if (::config.isInitialized) return
        pg = EmbeddedPostgres.builder()
            .setLocaleConfig("locale", "C")
            .setLocaleConfig("lc-messages", "C")
            .start()
        testCode = "k" + UUID.randomUUID().toString().replace("-", "").take(8)
        testToken = "t-" + UUID.randomUUID().toString().replace("-", "")
        clock = AdjustableClock(Clock.fixed(base, ZoneOffset.UTC)).also { it.adjustable = true }
        config = AppConfig.load(env(testClock = true))
        DatabaseFactory.init(config)
    }

    fun env(testClock: Boolean): Map<String, String> {
        val enc = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val mac = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val phones = (1..8).joinToString(",") { phone(it) }
        return mapOf(
            "PANTRY_PROFILE" to "test",
            "PANTRY_TEST_CLOCK" to if (testClock) "on" else "",
            "PANTRY_TEST_TOKEN" to testToken,
            "PANTRY_DB_URL" to pg.getJdbcUrl("postgres", "postgres"),
            "PANTRY_DB_USER" to "postgres",
            "PANTRY_DB_PASSWORD" to "",
            "PANTRY_PHONE_ENC_KEY" to Base64.getEncoder().encodeToString(enc),
            "PANTRY_PHONE_HMAC_KEY" to Base64.getEncoder().encodeToString(mac),
            "AUTH_MODE" to "test",
            "AUTH_TEST_CODE" to testCode,
            "AUTH_TEST_PHONES" to phones,
            "PANTRY_COOKIE_SECURE" to "true",
        )
    }

    fun truncate() {
        DatabaseFactory.dataSource.connection.use { conn ->
            conn.createStatement().execute(
                """
                TRUNCATE auth_codes, sessions, user_settings, family_members, families,
                         items, item_tombstones, item_events, photos, batch_ops, barcode_cache, devices, users
                RESTART IDENTITY CASCADE
                """.trimIndent(),
            )
        }
    }

    fun stop() {
        if (::pg.isInitialized) pg.close()
    }
}
