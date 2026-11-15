package com.damy.pantry.data

import com.pantrykeeper.core.date.DateMath
import com.pantrykeeper.core.date.ShelfUnit
import com.pantrykeeper.core.reminder.ExpiryGroup
import com.pantrykeeper.core.reminder.ReminderRules
import kotlinx.datetime.LocalDate

const val INVITE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

enum class ListBucket { EXPIRED, SOON, MONTH, LATER }

fun listBucket(today: LocalDate, expiry: LocalDate): ListBucket = when (ReminderRules.groupOf(today, expiry)) {
    ExpiryGroup.EXPIRED -> ListBucket.EXPIRED
    ExpiryGroup.WITHIN_7 -> ListBucket.SOON
    ExpiryGroup.WITHIN_30 -> ListBucket.MONTH
    ExpiryGroup.LATER -> ListBucket.LATER
}

fun bucketTitle(bucket: ListBucket): String = when (bucket) {
    ListBucket.EXPIRED -> "已过期"
    ListBucket.SOON -> "0–7 天"
    ListBucket.MONTH -> "8–30 天"
    ListBucket.LATER -> "更远"
}

fun daysLabel(days: Int): String = when {
    days < 0 -> "已过期 ${-days} 天"
    days == 0 -> "今天到期"
    days <= 30 -> "$days 天后到期"
    else -> "还有 $days 天"
}

fun categoryLabel(category: String): String = when (category) {
    "medicine" -> "药品"
    "condiment" -> "调料"
    "other" -> "其他"
    else -> "食品"
}

data class ItemGroup(val bucket: ListBucket, val title: String, val items: List<CachedItem>)

fun groupItems(today: LocalDate, items: List<CachedItem>, query: String): List<ItemGroup> {
    val needle = query.trim()
    val visible = items.filter { needle.isEmpty() || needle in it.name }
    return ListBucket.entries.mapNotNull { bucket ->
        val rows = visible
            .filter { listBucket(today, LocalDate.parse(it.expiryDate)) == bucket }
            .sortedBy { it.expiryDate }
        if (rows.isEmpty()) null else ItemGroup(bucket, bucketTitle(bucket), rows)
    }
}

fun shelfExpiry(today: LocalDate, production: LocalDate?, value: Int, unit: ShelfUnit): LocalDate {
    val start = production ?: today
    return DateMath.addShelf(start, value, unit)
}

fun shelfOrigin(today: LocalDate, production: LocalDate?): String =
    if (production == null) "未填写生产日期，从入库当天（$today）起算。" else "从生产日期 $production 起算。"

fun undoBarText(action: String, count: Int, seconds: Int, skipped: Int): String {
    val verb = if (action == "discarded") "已处理" else "已用完"
    return "已将 $count 件标记为$verb，已移入历史 · 跳过 $skipped 件 · $seconds 秒"
}

fun normalizeInvite(raw: String): String? {
    val s = raw.trim().uppercase().replace(" ", "")
    if (s.length != 6 || s.any { it !in INVITE_ALPHABET }) return null
    return s
}

fun maskPhone(raw: String): String {
    val digits = raw.filter { it.isDigit() }
    if (digits.length < 7) return ""
    return digits.take(3) + "****" + digits.takeLast(4)
}
