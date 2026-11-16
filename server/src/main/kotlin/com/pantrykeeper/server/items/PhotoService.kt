package com.pantrykeeper.server.items

import com.pantrykeeper.server.config.AppConfig
import com.pantrykeeper.server.family.FamilyAccess
import com.pantrykeeper.server.family.orThrow
import com.pantrykeeper.server.http.ApiException
import com.pantrykeeper.server.photo.PhotoQuota
import com.pantrykeeper.server.sync.FamilyHub
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.imageio.ImageIO
import kotlin.io.path.exists
import kotlin.uuid.Uuid

class PhotoService(
    private val config: AppConfig,
    private val clock: Clock,
    private val hub: FamilyHub,
    private val freeBytes: (Path) -> Long,
) {
    private val quota = PhotoQuota(config.familyPhotoMb, config.minFreeMb)
    private val root: Path = Path.of(config.dataDir)

    fun upload(userId: Uuid, familyId: Uuid?, clientKind: String, rawItemId: String, bytes: ByteArray, contentType: String?): Long {
        if (clientKind != "app") throw ApiException(HttpStatusCode.Forbidden, "app_only", "网页不能上传照片")
        val family = familyId ?: throw ApiException(HttpStatusCode.Forbidden, "not_in_family", "不在家庭中")
        val itemId = runCatching { Uuid.parse(rawItemId) }.getOrElse { throw ApiException(HttpStatusCode.NotFound, "not_found", "未找到") }
        if (bytes.size > config.photoMaxBytes) throw ApiException(HttpStatusCode.PayloadTooLarge, "photo_too_large", "照片太大")
        val kind = sniff(bytes, contentType) ?: throw ApiException(HttpStatusCode.BadRequest, "unsupported_photo", "只接受 JPEG 或 WEBP")
        Files.createDirectories(root)
        val free = freeBytes(root)
        val used = transaction {
            queryOne("select coalesce(sum(bytes), 0) from photos where family_id = ?", { it.setObject(1, family.toJava()) }) { it.getLong(1) } ?: 0L
        }
        val decision = quota.evaluate(used, bytes.size.toLong(), free)
        if (!decision.allowed) {
            val code = if (decision.reject.name == "LOW_DISK") "low_disk" else "family_photo_quota"
            val status = if (decision.httpStatus == 507) HttpStatusCode.InsufficientStorage else HttpStatusCode.PayloadTooLarge
            val message = if (decision.httpStatus == 507) "服务器空间不足，物品已保存但照片未上传" else "这个家庭的照片空间已满"
            throw ApiException(status, code, message)
        }
        val previous = transaction {
            queryOne("select id from families where id = ? for update", { it.setObject(1, family.toJava()) }) { it.getObject(1) }
                ?: throw ApiException(HttpStatusCode.NotFound, "not_found", "未找到")
            val row = queryOne(
                "select photo_id from items where id = ? and family_id = ?",
                {
                    it.setObject(1, itemId.toJava())
                    it.setObject(2, family.toJava())
                },
            ) { rs -> rs.getObject(1, java.util.UUID::class.java)?.toKotlin() ?: Uuid.NIL }
                ?: throw ApiException(HttpStatusCode.NotFound, "not_found", "未找到")
            row.takeUnless { it == Uuid.NIL }
        }
        val photoId = Uuid.random()
        val dir = root.resolve(family.toString())
        Files.createDirectories(dir)
        val ext = if (kind == "webp") "webp" else "jpg"
        val main = dir.resolve("$photoId.$ext")
        val thumb = dir.resolve("$photoId.thumb.jpg")
        val tmpMain = dir.resolve("$photoId.$ext.part")
        val tmpThumb = dir.resolve("$photoId.thumb.jpg.part")
        Files.write(tmpMain, bytes)
        val thumbBytes = writeThumb(bytes, tmpThumb)
        val total = bytes.size + (thumbBytes?.size ?: 0)
        val saved = try {
            transaction {
                queryOne("select id from families where id = ? for update", { it.setObject(1, family.toJava()) }) { it.getObject(1) }
                    ?: throw ApiException(HttpStatusCode.NotFound, "not_found", "未找到")
                val owned = queryOne(
                    "select photo_id from items where id = ? and family_id = ? for update",
                    {
                        it.setObject(1, itemId.toJava())
                        it.setObject(2, family.toJava())
                    },
                ) { rs -> rs.getObject(1, java.util.UUID::class.java)?.toKotlin() ?: Uuid.NIL }
                    ?: throw ApiException(HttpStatusCode.NotFound, "not_found", "未找到")
                Files.move(tmpMain, main, StandardCopyOption.REPLACE_EXISTING)
                if (thumbBytes != null) {
                    Files.move(tmpThumb, thumb, StandardCopyOption.REPLACE_EXISTING)
                } else {
                    Files.deleteIfExists(tmpThumb)
                }
                val replaced = owned.takeUnless { it == Uuid.NIL } ?: previous
                if (replaced != null) deleteStored(replaced, family)
                val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                val rev = queryOne("select nextval('item_rev_seq')", {}) { it.getLong(1) }!!
                update(
                    "insert into photos (id, family_id, item_id, path, thumb_path, bytes, sha256, created_by, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ) {
                    it.setObject(1, photoId.toJava())
                    it.setObject(2, family.toJava())
                    it.setObject(3, itemId.toJava())
                    it.setString(4, root.relativize(main).toString())
                    if (thumbBytes == null) it.setNull(5, java.sql.Types.VARCHAR) else it.setString(5, root.relativize(thumb).toString())
                    it.setInt(6, total)
                    it.setBytes(7, MessageDigest.getInstance("SHA-256").digest(bytes))
                    it.setObject(8, userId.toJava())
                    it.setObject(9, now)
                }
                val updated = update("update items set photo_id = ?, updated_by = ?, updated_at = ?, rev = ? where id = ? and family_id = ?") {
                    it.setObject(1, photoId.toJava())
                    it.setObject(2, userId.toJava())
                    it.setObject(3, now)
                    it.setLong(4, rev)
                    it.setObject(5, itemId.toJava())
                    it.setObject(6, family.toJava())
                }
                if (updated == 0) throw ApiException(HttpStatusCode.NotFound, "not_found", "未找到")
                rev
            }
        } catch (e: Exception) {
            Files.deleteIfExists(tmpMain)
            Files.deleteIfExists(tmpThumb)
            Files.deleteIfExists(main)
            Files.deleteIfExists(thumb)
            throw e
        }
        hub.changed(family, saved)
        return saved
    }

    fun read(familyId: Uuid?, rawId: String, thumb: Boolean): PhotoBytes {
        val family = familyId ?: throw ApiException(HttpStatusCode.Forbidden, "not_in_family", "不在家庭中")
        val id = runCatching { Uuid.parse(rawId) }.getOrElse { throw ApiException(HttpStatusCode.NotFound, "not_found", "未找到") }
        val located = transaction {
            queryOne(
                "select family_id, path, thumb_path from photos where id = ?",
                { it.setObject(1, id.toJava()) },
            ) { rs ->
                Triple(
                    rs.getObject(1, java.util.UUID::class.java).toKotlin(),
                    rs.getString(2),
                    rs.getString(3),
                )
            }
        }
        if (located == null) throw ApiException(HttpStatusCode.NotFound, "not_found", "未找到")
        FamilyAccess.decide(family, located.first).orThrow()
        val relative = if (thumb && located.third != null) located.third else located.second
        val path = root.resolve(relative).normalize()
        if (!path.startsWith(root.normalize()) || !path.exists()) {
            throw ApiException(HttpStatusCode.NotFound, "not_found", "未找到")
        }
        val bytes = Files.readAllBytes(path)
        val type = if (path.toString().endsWith(".webp")) "image/webp" else "image/jpeg"
        return PhotoBytes(bytes, type)
    }

    fun deleteStored(photoId: Uuid?, family: Uuid) {
        if (photoId == null) return
        val paths = queryOne(
            "select path, thumb_path from photos where id = ? and family_id = ?",
            {
                it.setObject(1, photoId.toJava())
                it.setObject(2, family.toJava())
            },
        ) { rs -> rs.getString(1) to rs.getString(2) } ?: return
        update("delete from photos where id = ? and family_id = ?") {
            it.setObject(1, photoId.toJava())
            it.setObject(2, family.toJava())
        }
        listOfNotNull(paths.first, paths.second).forEach { relative ->
            val path = root.resolve(relative).normalize()
            if (path.startsWith(root.normalize())) Files.deleteIfExists(path)
        }
    }

    private fun sniff(bytes: ByteArray, contentType: String?): String? {
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) return "jpeg"
        if (bytes.size >= 12 &&
            bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
            bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray())
        ) {
            return "webp"
        }
        return when (contentType?.substringBefore(';')?.trim()?.lowercase()) {
            "image/jpeg", "image/jpg" -> null
            "image/webp" -> null
            else -> null
        }
    }

    private fun writeThumb(bytes: ByteArray, dest: Path): ByteArray? {
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        val max = 320
        val scale = minOf(1.0, max.toDouble() / maxOf(image.width, image.height))
        val w = maxOf(1, (image.width * scale).toInt())
        val h = maxOf(1, (image.height * scale).toInt())
        val scaled = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = scaled.createGraphics()
        g.drawImage(image.getScaledInstance(w, h, Image.SCALE_SMOOTH), 0, 0, null)
        g.dispose()
        Files.newOutputStream(dest).use { ImageIO.write(scaled, "jpg", it) }
        return Files.readAllBytes(dest)
    }
}

data class PhotoBytes(val bytes: ByteArray, val contentType: String)
