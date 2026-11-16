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
    const val RETRY_BODY = "照片还没上传，可以点重试，或到编辑页再传。"
    const val LOST_BODY = "照片未上传，而且已经不在这台手机里。请到物品编辑页重新拍摄或添加。"

    private val captures = LinkedHashMap<String, ByteArray>()
    private val pending = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    val items: StateFlow<Map<String, ByteArray>> = pending.asStateFlow()
    private val lostIds = MutableStateFlow<Set<String>>(emptySet())
    val lostItems: StateFlow<Set<String>> = lostIds.asStateFlow()
    private val dismissedLost = mutableSetOf<String>()

    @Volatile var directory: File? = null

    /** Test seam. Production reads the file. */
    internal var readFile: (File) -> ByteArray = { it.readBytes() }

    fun bind(dir: File) {
        synchronized(this) {
        directory = dir
        if (!dir.exists() && !dir.mkdirs()) {
            pending.value = emptyMap()
            return
        }
        val loaded = linkedMapOf<String, ByteArray>()
        val failed = mutableSetOf<String>()
        dir.listFiles()?.forEach { file ->
            if (!file.isFile || file.name.endsWith(".tmp") || !safeName(file.name)) {
                if (file.isFile && file.name.endsWith(".tmp")) file.delete()
                return@forEach
            }
            val bytes = runCatching { readFile(file) }.getOrNull()
            if (bytes == null || bytes.isEmpty()) {
                if (file.name !in dismissedLost) failed += file.name
                file.delete()
            } else {
                loaded[file.name] = bytes
            }
        }
            pending.value = loaded
            lostIds.value = failed
        }
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

    fun holdForItem(itemId: String, jpeg: ByteArray) {
        synchronized(this) {
        if (!safeName(itemId) || jpeg.isEmpty()) return
        val file = fileFor(itemId)
        if (file != null) {
            val wrote = runCatching { writeAtomically(file, jpeg) }.isSuccess
            if (!wrote) {
                if (itemId !in dismissedLost) lostIds.value = lostIds.value + itemId
                return
            }
        }
            lostIds.value = lostIds.value - itemId
            dismissedLost.remove(itemId)
            pending.value = pending.value + (itemId to jpeg)
        }
    }

    fun dismissLost(itemId: String) = synchronized(this) {
        dismissedLost += itemId
        lostIds.value = lostIds.value - itemId
    }

    /** Used by screenshot tests to show the lost-photo banner without a broken file. */
    fun showLost(itemId: String) = synchronized(this) {
        if (safeName(itemId)) lostIds.value = lostIds.value + itemId
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
    fun drop(ids: Collection<String>) {
        synchronized(this) {
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
    }

    fun retain(ids: Set<String>) = synchronized(this) {
        drop(pending.value.keys.filter { it !in ids })
    }

    fun clear() = synchronized(this) {
        captures.clear()
        pending.value = emptyMap()
        lostIds.value = emptySet()
        dismissedLost.clear()
        directory?.listFiles()?.forEach { if (it.isFile) it.delete() }
    }

    private fun writeAtomically(dest: File, jpeg: ByteArray) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        tmp.writeBytes(jpeg)
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    private fun fileFor(itemId: String): File? {
        val dir = directory ?: return null
        if (!safeName(itemId)) return null
        return File(dir, itemId)
    }

    private fun safeName(name: String): Boolean =
        name.isNotBlank() && name.none { it == '/' || it == '\\' } && name != "." && name != ".." && ".." !in name
}
