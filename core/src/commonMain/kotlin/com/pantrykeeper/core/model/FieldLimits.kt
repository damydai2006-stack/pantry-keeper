package com.pantrykeeper.core.model

object FieldLimits {
    const val FAMILY_NAME_MIN = 2
    const val FAMILY_NAME_MAX = 20
    const val ITEM_NAME_MAX = 40
    const val NOTE_MAX = 200
    const val LOCATION_MAX = 12
    const val DISPLAY_NAME_MAX = 20

    fun codePoints(value: String): Int = value.codePointCount(0, value.length)

    /** Keep at most [max] Unicode code points, never a dangling surrogate. */
    fun takeCodePoints(value: String, max: Int): String {
        if (max <= 0 || value.isEmpty()) return ""
        if (codePoints(value) <= max) return value
        return value.substring(0, value.offsetByCodePoints(0, max))
    }

    fun familyNameError(name: String): String? {
        val n = codePoints(name.trim())
        return if (n in FAMILY_NAME_MIN..FAMILY_NAME_MAX) null else "家庭名需要 2 到 20 个字"
    }

    fun itemNameError(name: String): String? {
        val n = codePoints(name.trim())
        return if (n in 1..ITEM_NAME_MAX) null else "物品名需要 1 到 40 个字"
    }

    fun noteError(note: String?): String? {
        if (note == null) return null
        return if (codePoints(note) <= NOTE_MAX) null else "备注最多 200 个字"
    }

    fun displayNameError(name: String): String? {
        val n = codePoints(name.trim())
        return if (n in 1..DISPLAY_NAME_MAX) null else "昵称需要 1 到 20 个字"
    }

    fun locationError(location: String?): String? {
        if (location == null) return null
        return if (codePoints(location) <= LOCATION_MAX) null else "位置最多 12 个字"
    }
}
