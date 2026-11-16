package com.pantrykeeper.server

import com.pantrykeeper.server.auth.AuthService
import com.pantrykeeper.server.auth.PhoneCrypto
import com.pantrykeeper.server.auth.SessionContext
import com.pantrykeeper.server.auth.SessionService
import com.pantrykeeper.server.config.AdjustableClock
import com.pantrykeeper.server.config.AppConfig
import com.pantrykeeper.server.config.AuthMode
import com.pantrykeeper.server.db.DatabaseFactory
import com.pantrykeeper.server.family.FamilyService
import com.pantrykeeper.server.family.FamilySignal
import com.pantrykeeper.server.family.PurgeService
import com.pantrykeeper.server.items.BarcodeFetcher
import com.pantrykeeper.server.items.BarcodeService
import com.pantrykeeper.server.items.CreateItemRequest
import com.pantrykeeper.server.items.ItemService
import com.pantrykeeper.server.items.OffBarcodeFetcher
import com.pantrykeeper.server.items.PhotoService
import com.pantrykeeper.server.sync.FamilyHub
import com.pantrykeeper.server.http.ApiException
import com.pantrykeeper.server.http.ErrorBody
import com.pantrykeeper.server.http.RequestFailures
import com.pantrykeeper.server.http.appJson
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.utils.io.readRemaining
import io.ktor.websocket.close
import kotlinx.io.readByteArray
import io.ktor.websocket.WebSocketSession
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import kotlin.uuid.Uuid

private val log = LoggerFactory.getLogger("pantry")

fun Application.module(
    config: AppConfig,
    clock: Clock,
    barcodeFetcher: BarcodeFetcher? = null,
    freeBytes: ((Path) -> Long)? = null,
) {
    if (config.authMode == AuthMode.TEST) {
        log.warn("AUTH_MODE=test is enabled. Test-code login is active only for the configured allowlist.")
    }
    DatabaseFactory.init(config)
    val crypto = PhoneCrypto(config.phoneEncKey, config.phoneHmacKey)
    val sessions = SessionService(clock)
    val auth = AuthService(config, clock, crypto, sessions)
    val families = FamilyService(clock)
    val purge = PurgeService(clock, java.nio.file.Path.of(config.dataDir))
    val hub = FamilyHub(CoroutineScope(SupervisorJob() + Dispatchers.IO), sessions::sessionOpen)
    val photos = PhotoService(config, clock, hub, freeBytes ?: { Files.getFileStore(it).usableSpace })
    val items = ItemService(clock, hub, photos)
    val barcodes = BarcodeService(clock, barcodeFetcher ?: OffBarcodeFetcher(config))
    families.signals = { signal ->
        when (signal) {
            is FamilySignal.Changed -> hub.changed(signal.familyId, runCatching { items.head(signal.familyId) }.getOrDefault(0L))
            is FamilySignal.Removed -> hub.removed(signal.userId)
        }
    }

    install(ContentNegotiation) { json(appJson) }
    install(WebSockets)
    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respond(cause.status, ErrorBody(cause.code, cause.message ?: ""))
        }
        exception<Throwable> { call, cause ->
            when {
                RequestFailures.isMediaType(cause) -> {
                    RequestFailures.client(cause)
                    call.respond(HttpStatusCode.UnsupportedMediaType, ErrorBody("unsupported_media_type", "请求格式不正确"))
                }
                RequestFailures.isClient(cause) -> {
                    RequestFailures.client(cause)
                    call.respond(HttpStatusCode.BadRequest, ErrorBody("bad_request", "请求格式不正确"))
                }
                else -> {
                    RequestFailures.server(cause)
                    call.respond(HttpStatusCode.InternalServerError, ErrorBody("internal", "服务器错误"))
                }
            }
        }
    }

    suspend fun DefaultWebSocketServerSession.bindSocket() {
        val ctx = call.requireSession(sessions, config)
        val family = families.currentFamilyId(ctx.userId)
        if (family == null) {
            close()
            return
        }
        hub.register(ctx.userId, family, ctx.sessionId, this)
        send(io.ktor.websocket.Frame.Text("""{"t":"ready"}"""))
        val watch = hub.watch(ctx.sessionId)
        try {
            for (frame in incoming) {
                if (frame is io.ktor.websocket.Frame.Close) break
                if (!sessions.sessionOpen(ctx.sessionId)) {
                    hub.revokeSession(ctx.sessionId)
                    break
                }
            }
        } finally {
            watch.cancel()
            hub.unregister(this)
        }
    }

    routing {
        webSocket("/ws") { bindSocket() }
        get("/healthz") { call.respond(mapOf("status" to "ok")) }
        get("/readyz") {
            val dbOk = try {
                transaction { exec("select 1") }
                true
            } catch (e: Exception) {
                log.warn("readyz database check failed: {}", e.javaClass.simpleName)
                false
            }
            val body = ReadyBody(
                status = if (dbOk) "ready" else "not_ready",
                testCodeMode = config.authMode == AuthMode.TEST,
                testClock = config.testClockEnabled,
                database = if (dbOk) "ok" else "down",
            )
            call.respond(if (dbOk) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable, body)
        }
        route("/api/v1") {
            post("/auth/code") {
                val body = call.receive<CodeRequest>()
                auth.requestCode(body.phone)
                call.respond(mapOf("status" to "ok"))
            }
            post("/auth/login") {
                val body = call.receive<LoginRequest>()
                val result = auth.login(body.phone, body.code, body.clientKind, body.deviceName)
                if (body.clientKind == "web") {
                    call.response.cookies.append(sessionCookie(result.token, config))
                    call.respond(LoginResponse(result.userId.toString(), result.displayName, result.clientKind, null))
                } else {
                    call.respond(LoginResponse(result.userId.toString(), result.displayName, result.clientKind, result.token))
                }
            }
            if (config.testClockEnabled && clock is AdjustableClock) {
                post("/test/clock") {
                    requireTestToken(call, config)
                    val body = call.receive<ClockRequest>()
                    clock.setOffsetSeconds(body.offsetSeconds)
                    call.respond(ClockResponse(clock.offsetSeconds()))
                }
                post("/test/purge-now") {
                    requireTestToken(call, config)
                    call.respond(PurgeResponse(purge.purge()))
                }
            }
            post("/auth/logout") {
                val ctx = call.requireSession(sessions, config)
                sessions.logout(ctx.sessionId)
                hub.revokeSession(ctx.sessionId)
                if (ctx.clientKind == "web") call.response.headers.append("Set-Cookie", clearCookie(config))
                call.respond(mapOf("status" to "ok"))
            }
            get("/me") {
                val ctx = call.requireSession(sessions, config)
                val profile = sessions.profile(ctx.userId)
                val familyId = families.currentFamilyId(ctx.userId)
                call.respond(MeResponse(profile.userId.toString(), profile.displayName, profile.notifyTime, familyId?.toString()))
            }
            patch("/me") {
                val ctx = call.requireSession(sessions, config)
                val body = call.receive<DisplayNameRequest>()
                val profile = sessions.updateDisplayName(ctx.userId, body.displayName)
                val familyId = families.currentFamilyId(ctx.userId)
                call.respond(MeResponse(profile.userId.toString(), profile.displayName, profile.notifyTime, familyId?.toString()))
            }
            put("/me/settings") {
                val ctx = call.requireSession(sessions, config)
                val body = call.receive<SettingsRequest>()
                sessions.updateNotifyTime(ctx.userId, body.notifyTime)
                call.respond(mapOf("status" to "ok"))
            }
            post("/families") {
                val ctx = call.requireSession(sessions, config)
                val body = call.receive<CreateFamilyRequest>()
                call.respond(families.create(ctx.userId, body.name))
            }
            post("/families/join") {
                val ctx = call.requireSession(sessions, config)
                val body = call.receive<JoinRequest>()
                call.respond(families.join(ctx.userId, body.code))
            }
            post("/families/leave") {
                val ctx = call.requireSession(sessions, config)
                families.leave(ctx.userId)
                call.respond(mapOf("status" to "ok"))
            }
            get("/families/current") {
                val ctx = call.requireSession(sessions, config)
                call.respond(families.current(ctx.userId))
            }
            patch("/families/current") {
                val ctx = call.requireSession(sessions, config)
                val body = call.receive<RenameRequest>()
                call.respond(families.rename(ctx.userId, body.name))
            }
            post("/families/current/invite/reset") {
                val ctx = call.requireSession(sessions, config)
                call.respond(families.resetInvite(ctx.userId))
            }
            delete("/families/current/members/{uid}") {
                val ctx = call.requireSession(sessions, config)
                val uid = call.parameters["uid"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                    ?: throw ApiException(HttpStatusCode.NotFound, "not_found", "未找到")
                families.remove(ctx.userId, uid)
                call.respond(mapOf("status" to "ok"))
            }
            post("/families/current/admin") {
                val ctx = call.requireSession(sessions, config)
                val body = call.receive<TransferRequest>()
                val uid = runCatching { Uuid.parse(body.userId) }.getOrNull()
                    ?: throw ApiException(HttpStatusCode.NotFound, "not_found", "未找到")
                call.respond(families.transfer(ctx.userId, uid))
            }
            webSocket("/ws") { bindSocket() }
            post("/items") {
                val ctx = call.requireSession(sessions, config)
                val body = call.receive<CreateItemRequest>()
                call.respond(items.create(ctx.userId, families.currentFamilyId(ctx.userId), body))
            }
            get("/items") {
                val ctx = call.requireSession(sessions, config)
                call.respond(items.list(families.currentFamilyId(ctx.userId), call.request.queryParameters["status"]))
            }
            get("/items/{id}") {
                val ctx = call.requireSession(sessions, config)
                val id = call.parameters["id"] ?: throw ApiException(HttpStatusCode.NotFound, "not_found", "未找到")
                call.respond(items.get(ctx.userId, families.currentFamilyId(ctx.userId), id))
            }
            patch("/items/{id}") {
                val ctx = call.requireSession(sessions, config)
                val id = call.parameters["id"] ?: throw ApiException(HttpStatusCode.NotFound, "not_found", "未找到")
                val body = call.receive<PatchItemRequest>()
                val result = items.patch(ctx.userId, families.currentFamilyId(ctx.userId), id, body.baseRev, body.fields)
                call.respond(PatchItemResponse(result.item, result.overwritten, result.message()))
            }
            post("/items/batch") {
                val ctx = call.requireSession(sessions, config)
                val body = call.receive<BatchItemRequest>()
                val result = items.batch(ctx.userId, families.currentFamilyId(ctx.userId), body.ids, body.action)
                call.respond(BatchItemResponse(result.undoToken, result.rev))
            }
            post("/items/batch/undo") {
                val ctx = call.requireSession(sessions, config)
                val body = call.receive<UndoItemRequest>()
                val result = items.undo(ctx.userId, families.currentFamilyId(ctx.userId), body.undoToken)
                call.respond(UndoItemResponse(result.skipped, result.restored, result.message()))
            }
            post("/items/{id}/photo") {
                val ctx = call.requireSession(sessions, config)
                val id = call.parameters["id"] ?: throw ApiException(HttpStatusCode.NotFound, "not_found", "未找到")
                var bytes: ByteArray? = null
                var type: String? = null
                val multipart = call.receiveMultipart()
                while (true) {
                    val part = multipart.readPart() ?: break
                    if (part is PartData.FileItem && bytes == null) {
                        bytes = part.provider().readRemaining().readByteArray()
                        type = part.contentType?.toString()
                    }
                }
                val payload = bytes ?: throw ApiException(HttpStatusCode.BadRequest, "photo_required", "没有照片")
                photos.upload(ctx.userId, families.currentFamilyId(ctx.userId), ctx.clientKind, id, payload, type)
                call.respond(items.get(ctx.userId, families.currentFamilyId(ctx.userId), id))
            }
            get("/photos/{id}") {
                val ctx = call.requireSession(sessions, config)
                val id = call.parameters["id"] ?: throw ApiException(HttpStatusCode.NotFound, "not_found", "未找到")
                val thumb = call.request.queryParameters["thumb"] == "1"
                val photo = photos.read(families.currentFamilyId(ctx.userId), id, thumb)
                call.respondBytes(photo.bytes, io.ktor.http.ContentType.parse(photo.contentType))
            }
            get("/barcodes/{code}") {
                val ctx = call.requireSession(sessions, config)
                val code = call.parameters["code"] ?: throw ApiException(HttpStatusCode.NotFound, "not_found", "未找到")
                call.respond(barcodes.lookup(ctx.userId, code))
            }
            get("/sync") {
                val ctx = call.requireSession(sessions, config)
                val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
                val payload = items.sync(ctx.userId, families.currentFamilyId(ctx.userId), since)
                val family = if (payload.reset) null else runCatching { families.current(ctx.userId) }.getOrNull()
                call.respond(
                    SyncResponse(
                        reset = payload.reset,
                        rev = payload.rev,
                        items = payload.items,
                        tombstones = payload.tombstones,
                        family = family,
                        notifyTime = payload.notifyTime,
                    ),
                )
            }
        }
    }
}

private fun ApplicationCall.requireSession(sessions: SessionService, config: AppConfig): SessionContext {
    val authorization = request.headers["Authorization"]
    val bearer = authorization?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()
    val cookie = request.cookies["pantry_session"]
    val (token, viaCookie) = when {
        !bearer.isNullOrEmpty() -> bearer to false
        !cookie.isNullOrEmpty() -> cookie to true
        else -> throw ApiException(HttpStatusCode.Unauthorized, "unauthorized", "请先登录")
    }
    if (viaCookie && request.httpMethod !in setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options)) {
        if (request.headers["X-Pantry-Request"] != "1") {
            throw ApiException(HttpStatusCode.Forbidden, "csrf", "缺少请求头")
        }
    }
    val ctx = sessions.authenticate(token) ?: throw ApiException(HttpStatusCode.Unauthorized, "unauthorized", "请先登录")
    if (viaCookie && ctx.slid) response.cookies.append(sessionCookie(token, config))
    return ctx
}

private fun requireTestToken(call: ApplicationCall, config: AppConfig) {
    val presented = call.request.headers["X-Pantry-Test-Token"].orEmpty()
    val ok = MessageDigest.isEqual(presented.toByteArray(), config.testToken.toByteArray())
    if (!ok) throw ApiException(HttpStatusCode.Unauthorized, "unauthorized", "未授权")
}

private fun sessionCookie(token: String, config: AppConfig) = Cookie(
    name = "pantry_session",
    value = token,
    encoding = CookieEncoding.RAW,
    maxAge = 30 * 24 * 60 * 60,
    path = "/",
    secure = config.cookieSecure,
    httpOnly = true,
    extensions = mapOf("SameSite" to "Strict"),
)

private fun clearCookie(config: AppConfig): String {
    val secure = if (config.cookieSecure) "; Secure" else ""
    return "pantry_session=; Path=/; Max-Age=0; HttpOnly$secure; SameSite=Strict"
}

@Serializable
data class CodeRequest(val phone: String)

@Serializable
data class LoginRequest(val phone: String, val code: String, val clientKind: String, val deviceName: String? = null)

@Serializable
data class LoginResponse(val userId: String, val displayName: String, val clientKind: String, val token: String? = null)

@Serializable
data class MeResponse(val userId: String, val displayName: String, val notifyTime: String, val familyId: String? = null)

@Serializable
data class SettingsRequest(val notifyTime: String)

@kotlinx.serialization.Serializable
data class DisplayNameRequest(val displayName: String)

@Serializable
data class CreateFamilyRequest(val name: String)

@Serializable
data class JoinRequest(val code: String)

@Serializable
data class RenameRequest(val name: String)

@Serializable
data class TransferRequest(val userId: String)

@Serializable
data class ClockRequest(val offsetSeconds: Long)

@Serializable
data class ClockResponse(val offsetSeconds: Long)

@Serializable
data class PurgeResponse(val purgedFamilies: Int)

@Serializable
data class ReadyBody(val status: String, val testCodeMode: Boolean, val testClock: Boolean, val database: String)

@Serializable
data class PatchItemRequest(val baseRev: Long, val fields: JsonObject)

@Serializable
data class PatchItemResponse(val item: com.pantrykeeper.server.items.ItemDto, val overwritten: List<com.pantrykeeper.server.items.Overwritten>, val message: String? = null)

@Serializable
data class BatchItemRequest(val ids: List<String>, val action: String)

@Serializable
data class BatchItemResponse(val undoToken: String? = null, val rev: Long)

@Serializable
data class UndoItemRequest(val undoToken: String)

@Serializable
data class UndoItemResponse(val skipped: Int, val restored: Int, val message: String? = null)

@Serializable
data class SyncResponse(
    val reset: Boolean,
    val rev: Long,
    val items: List<com.pantrykeeper.server.items.ItemDto> = emptyList(),
    val tombstones: List<com.pantrykeeper.server.items.TombstoneDto> = emptyList(),
    val family: com.pantrykeeper.server.family.FamilyView? = null,
    val notifyTime: String? = null,
)
