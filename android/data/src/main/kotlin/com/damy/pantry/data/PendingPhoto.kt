package com.damy.pantry.data

/** JPEG waiting to be uploaded with the item created from this capture. */
object PendingPhoto {
    var jpeg: ByteArray? = null
    var retryForItem: String? = null

    fun clear() {
        jpeg = null
        retryForItem = null
    }

    /** Bytes that may be attached to a newly created item. Null once the capture was abandoned. */
    fun bytesFor(itemId: String?): ByteArray? {
        val pending = jpeg ?: return null
        if (itemId.isNullOrBlank()) return null
        return pending
    }
}
