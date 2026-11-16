package com.damy.pantry.data

import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A capture owns its bytes until save. A failed upload is then owned by that item id,
 * both in memory and in the app-private directory, so a killed process can retry.
 */
object PendingPhoto {
    const val LOST = "照片未上传、已丢失"

    private val captures = LinkedHashMap<String, ByteArray>()
    private val pending = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    val items: StateFlow<Map<String, ByteArray>> = pending.asStateFlow()
    val lost = MutableStateFlow<String?>(null)

    @Volatile var directory: File? = null

    /** Test seam. Production reads the file. */
    internal var readFile: (File) -> ByteArray = { it.readBytes() }

    fun bind(dir: File) = synchronized(this) {
        directory = dir
        if (!dir.exists() && !dir.mkdirs()) {
            pending.value = emptyMap()
            lost.value = LOST
            return
        }
        val loaded = linkedMapOf<String, ByteArray>()
        var failed = false
        dir.listFiles()?.forEach { file ->
            if (!file.isFile || !safeName(file.name)) {
                if (file.isFile) file.delete()
                return@forEach
            }
            val bytes = runCatching { readFile(file) }.getOrNull()
            if (bytes == null || bytes.isEmpty()) {
                failed = true
                file.delete()
            } else {
                loaded[file.name] = bytes
            }
        }
        pending.value = loaded
        if (failed) lost.value = LOST
    }

    /** Drop in-flight captures and start a new one. Photos already owned by an item stay. */
    fun beginCapture(): String = synchronized(this) {
        captures.clear()
        UUID.randomUUID().toString()
    }

    fun storeCapture(captureId: String, jpeg: ByteArray) = synchronized(this) {
        if (captureId.isNotBlank()) captures[captureId] = jpeg
    }

    fun abandon(captureId: String?) = synchronized(this) {
        if (!captureId.isNullOrBlank()) captures.remove(captureId)
    }

    fun takeCapture(captureId: String?): ByteArray? = synchronized(this) {
        if (captureId.isNullOrBlank()) null else captures.remove(captureId)
    }

    fun holdForItem(itemId: String, jpeg: ByteArray) = synchronized(this) {
        if (!safeName(itemId) || jpeg.isEmpty()) return
        val file = fileFor(itemId)
        if (file != null) {
            val wrote = runCatching {
                file.parentFile?.mkdirs()
                file.writeBytes(jpeg)
            }.isSuccess
            if (!wrote) {
                lost.value = LOST
                return
            }
        }
        pending.value = pending.value + (itemId to jpeg)
    }

    fun bytesFor(ownerId: String?): ByteArray? = synchronized(this) {
        if (ownerId.isNullOrBlank()) return@synchronized null
        pending.value[ownerId] ?: captures[ownerId]
    }

    fun itemIds(): List<String> = pending.value.keys.toList()

    fun remove(ownerId: String?) {
        synchronized(this) {
            if (ownerId.isNullOrBlank()) return
            captures.remove(ownerId)
            if (ownerId in pending.value) pending.value = pending.value - ownerId
            fileFor(ownerId)?.delete()
        }
    }

    /** Drop retry photos for items that were deleted or left this family. */
    fun drop(ids: Collection<String>) = synchronized(this) {
        if (ids.isEmpty()) return
        val next = pending.value.toMutableMap()
        var changed = false
        ids.forEach { id ->
            captures.remove(id)
            if (next.remove(id) != null) changed = true
            fileFor(id)?.delete()
        }
        if (changed) pending.value = next
    }

    fun retain(ids: Set<String>) = synchronized(this) {
        drop(pending.value.keys.filter { it !in ids })
    }

    fun clear() = synchronized(this) {
        captures.clear()
        pending.value = emptyMap()
        lost.value = null
        directory?.listFiles()?.forEach { if (it.isFile) it.delete() }
    }

    private fun fileFor(itemId: String): File? {
        val dir = directory ?: return null
        if (!safeName(itemId)) return null
        return File(dir, itemId)
    }

    private fun safeName(name: String): Boolean =
        name.isNotBlank() && name.none { it == '/' || it == '\\' } && name != "." && name != ".." && ".." !in name
}
