package com.damy.pantry.data

import java.util.UUID

/**
 * Photos are owned by a capture id or, after a failed upload, by that item id.
 * A lookup never returns some other capture's bytes.
 */
object PendingPhoto {
    private val captures = LinkedHashMap<String, ByteArray>()
    private val items = LinkedHashMap<String, ByteArray>()

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
        if (itemId.isNotBlank()) items[itemId] = jpeg
    }

    fun bytesFor(ownerId: String?): ByteArray? = synchronized(this) {
        if (ownerId.isNullOrBlank()) return@synchronized null
        items[ownerId] ?: captures[ownerId]
    }

    fun itemIds(): List<String> = synchronized(this) { items.keys.toList() }

    fun remove(ownerId: String?) = synchronized(this) {
        if (ownerId.isNullOrBlank()) return@synchronized
        captures.remove(ownerId)
        items.remove(ownerId)
    }

    fun clear() = synchronized(this) {
        captures.clear()
        items.clear()
    }
}
