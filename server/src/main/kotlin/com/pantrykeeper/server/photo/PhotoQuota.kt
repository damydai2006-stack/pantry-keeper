package com.pantrykeeper.server.photo

enum class PhotoReject { NONE, FAMILY_QUOTA, LOW_DISK }

data class PhotoDecision(val allowed: Boolean, val reject: PhotoReject, val httpStatus: Int)

class PhotoQuota(familyLimitMb: Long, minFreeMb: Long) {
    val familyLimitBytes: Long = familyLimitMb * 1024L * 1024L
    val minFreeBytes: Long = minFreeMb * 1024L * 1024L

    fun evaluate(familyUsedBytes: Long, incomingBytes: Long, freeDiskBytes: Long): PhotoDecision {
        if (freeDiskBytes < minFreeBytes) return PhotoDecision(false, PhotoReject.LOW_DISK, 507)
        if (familyUsedBytes + incomingBytes > familyLimitBytes) {
            return PhotoDecision(false, PhotoReject.FAMILY_QUOTA, 413)
        }
        return PhotoDecision(true, PhotoReject.NONE, 200)
    }
}
