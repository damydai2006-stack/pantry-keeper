package com.pantrykeeper.core.reminder

import com.pantrykeeper.core.date.DateMath
import kotlinx.datetime.LocalDate

enum class ItemStatus { IN_STOCK, USED_UP, DISCARDED }

enum class Tier {
    D5,
    D10,
    D30,
    M2,
    M3,
    ;

    companion object {
        val DEFAULT: Set<Tier> = setOf(D30, D5)

        fun parse(raw: String): Tier? = entries.find { it.name.equals(raw.trim(), ignoreCase = true) }
    }
}

enum class ExpiryGroup { EXPIRED, WITHIN_7, WITHIN_30, LATER }

enum class ExpiryColor { RED, ORANGE, YELLOW, GREEN }

enum class NoticeKind { TIER, EXPIRY_TODAY, EXPIRED }

data class Notice(
    val kind: NoticeKind,
    val tier: Tier?,
    val daysRemaining: Int,
    val expiredDays: Int?,
)

object ReminderRules {
    fun daysRemaining(today: LocalDate, expiry: LocalDate): Int = DateMath.daysBetween(today, expiry)

    fun groupOf(today: LocalDate, expiry: LocalDate): ExpiryGroup {
        val d = daysRemaining(today, expiry)
        return when {
            d < 0 -> ExpiryGroup.EXPIRED
            d <= 7 -> ExpiryGroup.WITHIN_7
            d <= 30 -> ExpiryGroup.WITHIN_30
            else -> ExpiryGroup.LATER
        }
    }

    fun colorOf(group: ExpiryGroup): ExpiryColor = when (group) {
        ExpiryGroup.EXPIRED -> ExpiryColor.RED
        ExpiryGroup.WITHIN_7 -> ExpiryColor.ORANGE
        ExpiryGroup.WITHIN_30 -> ExpiryColor.YELLOW
        ExpiryGroup.LATER -> ExpiryColor.GREEN
    }

    fun effectiveExpiry(expiry: LocalDate, openedDate: LocalDate?, afterOpenDays: Int?): LocalDate {
        if (openedDate == null || afterOpenDays == null) return expiry
        val openedUntil = DateMath.plusDays(openedDate, afterOpenDays)
        return minOf(expiry, openedUntil)
    }

    fun triggerDate(expiry: LocalDate, tier: Tier): LocalDate = when (tier) {
        Tier.D5 -> DateMath.plusDays(expiry, -5)
        Tier.D10 -> DateMath.plusDays(expiry, -10)
        Tier.D30 -> DateMath.plusDays(expiry, -30)
        Tier.M2 -> DateMath.plusMonths(expiry, -2)
        Tier.M3 -> DateMath.plusMonths(expiry, -3)
    }

    fun notices(
        today: LocalDate,
        expiry: LocalDate,
        tiers: Set<Tier>,
        entryDate: LocalDate,
        status: ItemStatus = ItemStatus.IN_STOCK,
        openedDate: LocalDate? = null,
        afterOpenDays: Int? = null,
    ): List<Notice> {
        if (status != ItemStatus.IN_STOCK || tiers.isEmpty()) return emptyList()
        val effective = effectiveExpiry(expiry, openedDate, afterOpenDays)
        val out = mutableListOf<Notice>()
        val remaining = daysRemaining(today, effective)
        for (tier in tiers) {
            val trigger = triggerDate(effective, tier)
            if (trigger < entryDate) continue
            if (trigger == today) {
                out += Notice(NoticeKind.TIER, tier, remaining, null)
            }
        }
        if (today == effective && today >= entryDate) {
            out += Notice(NoticeKind.EXPIRY_TODAY, null, 0, null)
        }
        val daysAfter = DateMath.daysBetween(effective, today)
        if (daysAfter >= 1 && (daysAfter - 1) % 7 == 0 && today >= entryDate) {
            out += Notice(NoticeKind.EXPIRED, null, -daysAfter, daysAfter)
        }
        return out
    }

    fun phrase(name: String, notice: Notice): String = when (notice.kind) {
        NoticeKind.EXPIRY_TODAY -> "${name}今天到期"
        NoticeKind.EXPIRED -> "${name}已过期第 ${notice.expiredDays} 天"
        NoticeKind.TIER -> when (notice.tier) {
            Tier.M2 -> "$name 2 个月后到期"
            Tier.M3 -> "$name 3 个月后到期"
            else -> "$name ${notice.daysRemaining} 天后到期"
        }
    }

    /** Joins already-ordered phrases. At most five names, then “等 N 件”. */
    fun summary(lines: List<String>): String {
        if (lines.isEmpty()) return ""
        val head = lines.take(5)
        val rest = lines.size - head.size
        val body = if (rest > 0) head.joinToString("；") + "等 $rest 件" else head.joinToString("；")
        return "${lines.size} 件物品需要注意：$body"
    }
}
