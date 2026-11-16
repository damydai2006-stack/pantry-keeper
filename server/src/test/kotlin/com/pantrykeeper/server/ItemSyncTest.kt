package com.pantrykeeper.server

import com.pantrykeeper.server.config.AdjustableClock
import com.pantrykeeper.server.config.AppConfig
import com.pantrykeeper.server.db.DatabaseFactory
import com.pantrykeeper.server.http.appJson
import com.pantrykeeper.server.items.BarcodeFetcher
import com.pantrykeeper.server.items.BarcodeHit
import com.pantrykeeper.server.items.BarcodeLookup
import com.pantrykeeper.server.items.CreateItemRequest
import com.pantrykeeper.server.items.ItemDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ItemSyncTest {
    @Test
    fun createDefaultsShelfLifeAndValidation() = app { client ->
        val token = login(client, phone(21))
        val family = createFamily(client, token, "同步家庭")
        val missing = client.post("/api/v1/items") {
            bearer(token)
            contentType(ContentType.Application.Json)
            setBody(CreateItemRequest(name = "没有日期"))
        }
        assertEquals(HttpStatusCode.BadRequest, missing.status)
        val created = client.post("/api/v1/items") {
            bearer(token)
            contentType(ContentType.Application.Json)
            setBody(CreateItemRequest(name = "牛奶", expiryDate = "2027-03-15"))
        }
        assertEquals(HttpStatusCode.OK, created.status)
        val item = created.body<ItemDto>()
        assertEquals("food", item.category)
        assertEquals(1, item.quantity)
        assertEquals(listOf("D30", "D5"), item.reminderTiers)
        assertEquals("in_stock", item.status)
        val fromShelf = client.post("/api/v1/items") {
            bearer(token)
            contentType(ContentType.Application.Json)
            setBody(CreateItemRequest(name = "面粉", productionDate = "2026-01-31", shelfLifeValue = 1, shelfLifeUnit = "month"))
        }.body<ItemDto>()
        assertEquals("2026-02-28", fromShelf.expiryDate)
        val tooLong = client.post("/api/v1/items") {
            bearer(token)
            contentType(ContentType.Application.Json)
            setBody(CreateItemRequest(name = "名".repeat(41), expiryDate = "2027-01-01"))
        }
        assertEquals(HttpStatusCode.BadRequest, tooLong.status)
        assertEquals(family.name, "同步家庭")
        val emptyTiers = client.post("/api/v1/items") {
            bearer(token)
            contentType(ContentType.Application.Json)
            setBody(CreateItemRequest(name = "静音", expiryDate = "2027-01-01", reminderTiers = emptyList()))
        }.body<ItemDto>()
        assertTrue(emptyTiers.reminderTiers.isEmpty())
    }

    @Test
    fun lastWriteWinsDeleteBeatsEditAndBatchUndo() = app { client ->
        val a = login(client, phone(22))
        val b = login(client, phone(23))
        createFamily(client, a, "并发家庭")
        val code = client.get("/api/v1/families/current") { bearer(a) }.body<com.pantrykeeper.server.family.FamilyView>().inviteCode!!
        client.post("/api/v1/families/join") {
            bearer(b)
            contentType(ContentType.Application.Json)
            setBody(JoinRequest(code))
        }
        nameUser(client, a, "甲")
        nameUser(client, b, "乙")
        val item = client.post("/api/v1/items") {
            bearer(a)
            contentType(ContentType.Application.Json)
            setBody(CreateItemRequest(name = "酱油", expiryDate = "2027-06-01", reminderTiers = listOf("D10")))
        }.body<ItemDto>()
        val other = client.post("/api/v1/items") {
            bearer(a)
            contentType(ContentType.Application.Json)
            setBody(CreateItemRequest(name = "米", expiryDate = "2027-08-01"))
        }.body<ItemDto>()
        val renamed = patch(client, a, item.id, item.rev, buildJsonObject { put("name", "生抽") })
        val dated = patch(client, b, item.id, item.rev, buildJsonObject { put("expiry_date", "2027-07-01") })
        assertEquals("生抽", dated.item.name)
        assertEquals("2027-07-01", dated.item.expiryDate)
        assertTrue(dated.overwritten.isEmpty())
        val clash = patch(client, b, item.id, item.rev, buildJsonObject { put("name", "乙的名字") })
        assertEquals("乙的名字", clash.item.name)
        assertEquals("name", clash.overwritten.single().field)
        assertEquals("甲", clash.overwritten.single().byName)
        assertTrue(clash.message!!.contains("已被甲修改"))

        val batch = client.post("/api/v1/items/batch") {
            bearer(a)
            contentType(ContentType.Application.Json)
            setBody(BatchItemRequest(listOf(item.id, other.id, UUID.randomUUID().toString()), "used_up"))
        }
        assertEquals(HttpStatusCode.NotFound, batch.status)
        assertEquals("in_stock", client.get("/api/v1/items/${item.id}") { bearer(a) }.body<ItemDto>().status)
        assertEquals("in_stock", client.get("/api/v1/items/${other.id}") { bearer(a) }.body<ItemDto>().status)

        val marked = client.post("/api/v1/items/batch") {
            bearer(a)
            contentType(ContentType.Application.Json)
            setBody(BatchItemRequest(listOf(item.id), "used_up"))
        }
        assertEquals(HttpStatusCode.OK, marked.status)
        val undoToken = marked.body<BatchItemResponse>().undoToken
        assertNotNull(undoToken)
        val afterMark = client.get("/api/v1/items/${item.id}") { bearer(a) }.body<ItemDto>()
        assertEquals("used_up", afterMark.status)
        assertEquals(listOf("D10"), afterMark.reminderTiers)
        patch(client, b, item.id, afterMark.rev, buildJsonObject { put("status", "discarded") })
        val undo = client.post("/api/v1/items/batch/undo") {
            bearer(a)
            contentType(ContentType.Application.Json)
            setBody(UndoItemRequest(undoToken))
        }
        assertEquals(HttpStatusCode.OK, undo.status)
        val undoBody = undo.body<UndoItemResponse>()
        assertEquals(1, undoBody.skipped)
        assertTrue(undoBody.message!!.contains("1 件已被他人修改，未撤销"))
        assertEquals("discarded", client.get("/api/v1/items/${item.id}") { bearer(a) }.body<ItemDto>().status)

        val restored = client.post("/api/v1/items/batch") {
            bearer(a)
            contentType(ContentType.Application.Json)
            setBody(BatchItemRequest(listOf(item.id), "restore"))
        }
        assertEquals(HttpStatusCode.OK, restored.status)
        val back = client.get("/api/v1/items/${item.id}") { bearer(a) }.body<ItemDto>()
        assertEquals("in_stock", back.status)
        assertEquals(listOf("D10"), back.reminderTiers)

        client.post("/api/v1/items/batch") {
            bearer(a)
            contentType(ContentType.Application.Json)
            setBody(BatchItemRequest(listOf(other.id), "delete"))
        }
        val gone = client.patch("/api/v1/items/${other.id}") {
            bearer(b)
            contentType(ContentType.Application.Json)
            setBody(PatchItemRequest(back.rev, buildJsonObject { put("name", "救回") }))
        }
        assertEquals(HttpStatusCode.Gone, gone.status)
        assertTrue(gone.bodyAsText().contains("已被"))
        val sync = client.get("/api/v1/sync?since=0") { bearer(a) }.body<SyncResponse>()
        assertTrue(sync.tombstones.any { it.itemId == other.id })
        assertFalse(sync.items.any { it.id == other.id })
        val later = client.get("/api/v1/sync?since=${sync.rev}") { bearer(a) }.body<SyncResponse>()
        assertTrue(later.items.isEmpty())
        assertTrue(later.tombstones.isEmpty())
        assertFalse(later.reset)
    }

    @Test
    fun websocketNudgeAndRemovedMember() = app { client ->
        val admin = login(client, phone(24))
        val member = login(client, phone(25))
        createFamily(client, admin, "通知家庭")
        val code = client.get("/api/v1/families/current") { bearer(admin) }.body<com.pantrykeeper.server.family.FamilyView>().inviteCode!!
        client.post("/api/v1/families/join") {
            bearer(member)
            contentType(ContentType.Application.Json)
            setBody(JoinRequest(code))
        }
        val ws = wsClient()
        repeat(20) { n ->
            val started = CountDownLatch(1)
            val frame = AtomicReference<String>()
            val thread = Thread {
                runBlocking {
                    ws.webSocket("/api/v1/ws", request = { header(HttpHeaders.Authorization, "Bearer $member") }) {
                        val ready = (incoming.receive() as Frame.Text).readText()
                        check(ready.contains("\"t\":\"ready\""))
                        started.countDown()
                        frame.set((incoming.receive() as Frame.Text).readText())
                    }
                }
            }
            thread.isDaemon = true
            thread.start()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val before = System.nanoTime()
            client.post("/api/v1/items") {
                bearer(admin)
                contentType(ContentType.Application.Json)
                setBody(CreateItemRequest(name = "通知物$n", expiryDate = "2027-01-02"))
            }
            thread.join(10_000)
            val text = frame.get()
            assertNotNull(text)
            assertTrue(text.contains("\"t\":\"changed\""))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 10_000)
        }

        val removedGate = CountDownLatch(1)
        val removedText = AtomicReference<String>()
        val thread2 = Thread {
            runBlocking {
                ws.webSocket("/api/v1/ws", request = { header(HttpHeaders.Authorization, "Bearer $member") }) {
                    val ready = (incoming.receive() as Frame.Text).readText()
                    check(ready.contains("\"t\":\"ready\""))
                    removedGate.countDown()
                    removedText.set((incoming.receive() as Frame.Text).readText())
                }
            }
        }
        thread2.isDaemon = true
        thread2.start()
        assertTrue(removedGate.await(5, TimeUnit.SECONDS))
        val memberId = client.get("/api/v1/me") { bearer(member) }.body<MeResponse>().userId
        val removed = client.delete("/api/v1/families/current/members/$memberId") { bearer(admin) }
        assertEquals(HttpStatusCode.OK, removed.status)
        thread2.join(10_000)
        assertTrue(removedText.get().contains("removed_from_family"))
        val next = client.get("/api/v1/items") { bearer(member) }
        assertEquals(HttpStatusCode.Forbidden, next.status)
        assertFalse(next.bodyAsText().contains("通知物"))
    }

    @Test
    fun photoFamilyCheckQuotaAndDisk() = app { client ->
        val a = login(client, phone(26))
        val b = login(client, phone(27))
        createFamily(client, a, "照片甲")
        val item = client.post("/api/v1/items") {
            bearer(a)
            contentType(ContentType.Application.Json)
            setBody(CreateItemRequest(name = "盒子", expiryDate = "2027-04-01"))
        }.body<ItemDto>()
        val uploaded = client.post("/api/v1/items/${item.id}/photo") {
            bearer(a)
            setBody(MultiPartFormDataContent(formData {
                append("file", tinyJpeg(), Headers.build {
                    append(HttpHeaders.ContentType, ContentType.Image.JPEG.toString())
                    append(HttpHeaders.ContentDisposition, "filename=\"box.jpg\"")
                })
            }))
        }
        assertEquals(HttpStatusCode.OK, uploaded.status, uploaded.bodyAsText())
        val photoId = uploaded.body<ItemDto>().photoId
        assertNotNull(photoId)
        val own = client.get("/api/v1/photos/$photoId?thumb=1") { bearer(a) }
        assertEquals(HttpStatusCode.OK, own.status)
        assertTrue(own.bodyAsText().length > 10 || own.headers[HttpHeaders.ContentType]?.contains("image") == true)

        createFamily(client, b, "照片乙")
        val foreign = client.get("/api/v1/photos/$photoId") { bearer(b) }
        assertEquals(HttpStatusCode.NotFound, foreign.status)
        val raw = foreign.bodyAsText()
        assertFalse(raw.startsWith("\u00FF"))
        assertTrue(raw.contains("not_found"))
        assertFalse(raw.contains("照片甲"))

        val tiny = Box.config.copy(familyPhotoMb = 0)
        app(config = tiny) { limited ->
            val owner = login(limited, phone(28))
            createFamily(limited, owner, "配额家庭")
            val row = limited.post("/api/v1/items") {
                bearer(owner)
                contentType(ContentType.Application.Json)
                setBody(CreateItemRequest(name = "大图", expiryDate = "2027-05-01"))
            }.body<ItemDto>()
            val denied = limited.post("/api/v1/items/${row.id}/photo") {
                bearer(owner)
                setBody(MultiPartFormDataContent(formData {
                    append("file", tinyJpeg(), Headers.build {
                        append(HttpHeaders.ContentType, ContentType.Image.JPEG.toString())
                        append(HttpHeaders.ContentDisposition, "filename=\"big.jpg\"")
                    })
                }))
            }
            assertEquals(HttpStatusCode.PayloadTooLarge, denied.status)
            assertTrue(denied.bodyAsText().contains("family_photo_quota"))
        }
        app(freeBytes = { 1024L }) { tight ->
            val owner = login(tight, phone(29))
            createFamily(tight, owner, "磁盘家庭")
            val row = tight.post("/api/v1/items") {
                bearer(owner)
                contentType(ContentType.Application.Json)
                setBody(CreateItemRequest(name = "磁盘", expiryDate = "2027-05-02"))
            }.body<ItemDto>()
            val denied = tight.post("/api/v1/items/${row.id}/photo") {
                bearer(owner)
                setBody(MultiPartFormDataContent(formData {
                    append("file", tinyJpeg(), Headers.build {
                        append(HttpHeaders.ContentType, ContentType.Image.JPEG.toString())
                        append(HttpHeaders.ContentDisposition, "filename=\"disk.jpg\"")
                    })
                }))
            }
            assertEquals(HttpStatusCode.InsufficientStorage, denied.status)
            assertTrue(denied.bodyAsText().contains("low_disk"))
        }
    }

    @Test
    fun barcodeCacheAndGracefulFailure() = app { client ->
        val token = login(client, phone(30))
        val hit = client.get("/api/v1/barcodes/12345670") { bearer(token) }.body<BarcodeLookup>()
        assertTrue(hit.found)
        assertEquals("牛奶", hit.name)
        val again = client.get("/api/v1/barcodes/12345670") { bearer(token) }.body<BarcodeLookup>()
        assertEquals("牛奶", again.name)
        assertEquals(1, Box.fetches.get())
        val down = client.get("/api/v1/barcodes/00000000") { bearer(token) }.body<BarcodeLookup>()
        assertFalse(down.found)
        assertEquals("lookup_failed", down.reason)
        val trace = client.get("/api/v1/barcodes/${"1".repeat(20)}") { bearer(token) }
        assertEquals(HttpStatusCode.OK, trace.status)
        assertEquals("trace_code", trace.body<BarcodeLookup>().reason)
    }

    @Test
    fun logoutAndExpiryCloseOnlyThatSocket() = app { client ->
        val admin = login(client, phone(31))
        val member = login(client, phone(32))
        val otherDevice = login(client, phone(32))
        createFamily(client, admin, "会话家庭")
        val code = client.get("/api/v1/families/current") { bearer(admin) }.body<com.pantrykeeper.server.family.FamilyView>().inviteCode!!
        client.post("/api/v1/families/join") {
            bearer(member)
            contentType(ContentType.Application.Json)
            setBody(JoinRequest(code))
        }
        val ws = wsClient()
        val loggedOut = socketFrames(ws, member)
        val kept = socketFrames(ws, otherDevice)
        assertTrue(loggedOut.started.await(5, TimeUnit.SECONDS))
        assertTrue(kept.started.await(5, TimeUnit.SECONDS))
        Thread.sleep(200)
        val bye = client.post("/api/v1/auth/logout") { bearer(member) }
        assertEquals(HttpStatusCode.OK, bye.status)
        val denied = client.get("/api/v1/me") { bearer(member) }
        assertEquals(HttpStatusCode.Unauthorized, denied.status)
        loggedOut.thread.join(5_000)
        assertTrue(
            loggedOut.frames.any { it.contains("session_revoked") },
            "alive=${loggedOut.thread.isAlive} frames=${loggedOut.frames}",
        )
        assertFalse(loggedOut.frames.any { it.contains("changed") })

        client.post("/api/v1/items") {
            bearer(admin)
            contentType(ContentType.Application.Json)
            setBody(CreateItemRequest(name = "登出后", expiryDate = "2027-09-01"))
        }
        assertTrue(awaitFrame(kept, "changed"), kept.frames.toString())
        assertFalse(loggedOut.frames.any { it.contains("changed") })

        val expiring = login(client, phone(33))
        createFamily(client, expiring, "过期家庭")
        val expiredSock = socketFrames(ws, expiring)
        assertTrue(expiredSock.started.await(5, TimeUnit.SECONDS))
        client.post("/api/v1/items") {
            bearer(expiring)
            contentType(ContentType.Application.Json)
            setBody(CreateItemRequest(name = "仍在线", expiryDate = "2027-10-01"))
        }
        assertTrue(awaitFrame(expiredSock, "changed"), expiredSock.frames.toString())
        Box.clock.setOffsetSeconds(Box.clock.offsetSeconds() + 31L * 24 * 60 * 60)
        assertTrue(awaitFrame(expiredSock, "session_revoked"), expiredSock.frames.toString())
        val still = client.get("/api/v1/me") { bearer(expiring) }
        assertEquals(HttpStatusCode.Unauthorized, still.status)
    }

    @Test
    fun undoTokenIsSingleUseInsideTheWindow() = app { client ->
        val token = login(client, phone(34))
        createFamily(client, token, "撤销家庭")
        val item = client.post("/api/v1/items") {
            bearer(token)
            contentType(ContentType.Application.Json)
            setBody(CreateItemRequest(name = "面包", expiryDate = "2027-02-02"))
        }.body<ItemDto>()
        val marked = client.post("/api/v1/items/batch") {
            bearer(token)
            contentType(ContentType.Application.Json)
            setBody(BatchItemRequest(listOf(item.id), "used_up"))
        }
        assertEquals(HttpStatusCode.OK, marked.status)
        val undoToken = marked.body<BatchItemResponse>().undoToken!!
        val first = client.post("/api/v1/items/batch/undo") {
            bearer(token)
            contentType(ContentType.Application.Json)
            setBody(UndoItemRequest(undoToken))
        }
        assertEquals(HttpStatusCode.OK, first.status)
        val after = client.get("/api/v1/items/${item.id}") { bearer(token) }.body<ItemDto>()
        assertEquals("in_stock", after.status)
        val second = client.post("/api/v1/items/batch/undo") {
            bearer(token)
            contentType(ContentType.Application.Json)
            setBody(UndoItemRequest(undoToken))
        }
        assertEquals(HttpStatusCode.NotFound, second.status)
        val untouched = client.get("/api/v1/items/${item.id}") { bearer(token) }.body<ItemDto>()
        assertEquals(after.rev, untouched.rev)
        assertEquals("in_stock", untouched.status)

        val again = client.post("/api/v1/items/batch") {
            bearer(token)
            contentType(ContentType.Application.Json)
            setBody(BatchItemRequest(listOf(item.id), "discarded"))
        }
        val lateToken = again.body<BatchItemResponse>().undoToken!!
        Box.clock.setOffsetSeconds(Box.clock.offsetSeconds() + 61)
        val late = client.post("/api/v1/items/batch/undo") {
            bearer(token)
            contentType(ContentType.Application.Json)
            setBody(UndoItemRequest(lateToken))
        }
        assertEquals(HttpStatusCode.Gone, late.status)
        assertTrue(late.bodyAsText().contains("undo_expired"))
        assertEquals("discarded", client.get("/api/v1/items/${item.id}") { bearer(token) }.body<ItemDto>().status)
    }

    @Test
    fun foreignPhotoIsRejectedBeforeAnyFileIsWritten() = app { client ->
        val owner = login(client, phone(35))
        val stranger = login(client, phone(36))
        createFamily(client, owner, "照片原主")
        val item = client.post("/api/v1/items") {
            bearer(owner)
            contentType(ContentType.Application.Json)
            setBody(CreateItemRequest(name = "罐头", expiryDate = "2027-03-03"))
        }.body<ItemDto>()
        createFamily(client, stranger, "照片外人")
        val before = photoFileCount()
        val denied = client.post("/api/v1/items/${item.id}/photo") {
            bearer(stranger)
            setBody(MultiPartFormDataContent(formData {
                append("file", tinyJpeg(), Headers.build {
                    append(HttpHeaders.ContentType, ContentType.Image.JPEG.toString())
                    append(HttpHeaders.ContentDisposition, "filename=\"nope.jpg\"")
                })
            }))
        }
        assertEquals(HttpStatusCode.NotFound, denied.status)
        assertTrue(denied.bodyAsText().contains("not_found"))
        assertFalse(denied.bodyAsText().contains("罐头"))
        assertEquals(before, photoFileCount())
        val unknown = client.post("/api/v1/items/${UUID.randomUUID()}/photo") {
            bearer(stranger)
            setBody(MultiPartFormDataContent(formData {
                append("file", tinyJpeg(), Headers.build {
                    append(HttpHeaders.ContentType, ContentType.Image.JPEG.toString())
                    append(HttpHeaders.ContentDisposition, "filename=\"missing.jpg\"")
                })
            }))
        }
        assertEquals(HttpStatusCode.NotFound, unknown.status)
        assertEquals(before, photoFileCount())
    }
}

private fun HttpRequestBuilder.bearer(token: String) {
    header(HttpHeaders.Authorization, "Bearer $token")
}

private suspend fun patch(client: HttpClient, token: String, id: String, baseRev: Long, fields: kotlinx.serialization.json.JsonObject): PatchItemResponse {
    val response = client.patch("/api/v1/items/$id") {
        bearer(token)
        contentType(ContentType.Application.Json)
        setBody(PatchItemRequest(baseRev, fields))
    }
    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    return response.body()
}

private fun phone(n: Int): String = "13" + n.toString().padStart(9, '0')

private fun tinyJpeg(): ByteArray {
    val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
    val out = ByteArrayOutputStream()
    ImageIO.write(image, "jpg", out)
    return out.toByteArray()
}

private class LiveSocket(
    val started: CountDownLatch,
    val frames: MutableList<String>,
    val thread: Thread,
)

private fun socketFrames(ws: HttpClient, token: String): LiveSocket {
    val started = CountDownLatch(1)
    val frames = java.util.Collections.synchronizedList(mutableListOf<String>())
    val thread = Thread {
        runBlocking {
            runCatching {
                ws.webSocket("/api/v1/ws", request = { header(HttpHeaders.Authorization, "Bearer $token") }) {
                    started.countDown()
                    for (frame in incoming) {
                        if (frame is Frame.Text) frames.add(frame.readText())
                    }
                }
            }
        }
    }
    thread.isDaemon = true
    thread.start()
    return LiveSocket(started, frames, thread)
}

private fun awaitFrame(sock: LiveSocket, needle: String): Boolean {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (System.nanoTime() < deadline) {
        if (sock.frames.any { it.contains(needle) }) return true
        Thread.sleep(50)
    }
    return false
}

private fun photoFileCount(): Int {
    val root = java.nio.file.Path.of(Box.config.dataDir)
    if (!Files.exists(root)) return 0
    return Files.walk(root).use { stream -> stream.filter { Files.isRegularFile(it) }.count().toInt() }
}

private suspend fun login(client: HttpClient, phone: String): String {
    Box.clock.setOffsetSeconds(Box.clock.offsetSeconds() + 120)
    val code = client.post("/api/v1/auth/code") {
        contentType(ContentType.Application.Json)
        setBody(CodeRequest(phone))
    }
    assertEquals(HttpStatusCode.OK, code.status)
    val res = client.post("/api/v1/auth/login") {
        contentType(ContentType.Application.Json)
        setBody(LoginRequest(phone, Box.testCode, "app", null))
    }
    assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
    return res.body<LoginResponse>().token!!
}

private suspend fun createFamily(client: HttpClient, token: String, name: String) =
    client.post("/api/v1/families") {
        bearer(token)
        contentType(ContentType.Application.Json)
        setBody(CreateFamilyRequest(name))
    }.body<com.pantrykeeper.server.family.FamilyView>()

private suspend fun nameUser(client: HttpClient, token: String, name: String) {
    val id = client.get("/api/v1/me") { bearer(token) }.body<MeResponse>().userId
    DatabaseFactory.dataSource.connection.use { conn ->
        conn.prepareStatement("update users set display_name = ? where id = ?").use {
            it.setString(1, name)
            it.setObject(2, java.util.UUID.fromString(id))
            check(it.executeUpdate() == 1) { "display name not updated" }
        }
    }
}

private fun app(
    config: AppConfig? = null,
    freeBytes: ((java.nio.file.Path) -> Long)? = null,
    block: suspend io.ktor.server.testing.ApplicationTestBuilder.(HttpClient) -> Unit,
) {
    Box.ensure()
    val active = config ?: Box.config
    testApplication {
        application {
            module(active, Box.clock, Box.fetcher, freeBytes)
        }
        val client = createClient {
            install(ContentNegotiation) { json(appJson) }
        }
        block(client)
    }
}

private fun io.ktor.server.testing.ApplicationTestBuilder.wsClient() = createClient {
    install(ContentNegotiation) { json(appJson) }
    install(WebSockets)
}

private object Box {
    lateinit var pg: EmbeddedPostgres
    lateinit var config: AppConfig
    lateinit var clock: AdjustableClock
    lateinit var testCode: String
    val fetches = java.util.concurrent.atomic.AtomicInteger()
    val fetcher = BarcodeFetcher { code ->
        fetches.incrementAndGet()
        if (code == "00000000") error("upstream down")
        BarcodeHit("牛奶", "food")
    }

    fun ensure() {
        if (::config.isInitialized) return
        pg = EmbeddedPostgres.builder().setLocaleConfig("locale", "C").setLocaleConfig("lc-messages", "C").start()
        testCode = "k" + UUID.randomUUID().toString().replace("-", "").take(8)
        clock = AdjustableClock(Clock.fixed(Instant.parse("2026-11-15T00:00:00Z"), ZoneOffset.UTC)).also { it.adjustable = true }
        val dir = Files.createTempDirectory("pantry-photos")
        val enc = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val mac = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        config = AppConfig.load(
            mapOf(
                "PANTRY_PROFILE" to "test",
                "PANTRY_TEST_CLOCK" to "on",
                "PANTRY_TEST_TOKEN" to ("t-" + UUID.randomUUID().toString().replace("-", "")),
                "PANTRY_DB_URL" to pg.getJdbcUrl("postgres", "postgres"),
                "PANTRY_DB_USER" to "postgres",
                "PANTRY_DB_PASSWORD" to "",
                "PANTRY_PHONE_ENC_KEY" to Base64.getEncoder().encodeToString(enc),
                "PANTRY_PHONE_HMAC_KEY" to Base64.getEncoder().encodeToString(mac),
                "AUTH_MODE" to "test",
                "AUTH_TEST_CODE" to testCode,
                "AUTH_TEST_PHONES" to (21..36).joinToString(",") { phone(it) },
                "PANTRY_COOKIE_SECURE" to "true",
                "PANTRY_DATA_DIR" to dir.toString(),
            ),
        )
        DatabaseFactory.init(config)
    }
}
