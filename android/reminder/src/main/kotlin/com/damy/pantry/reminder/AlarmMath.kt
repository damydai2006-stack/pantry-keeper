package com.damy.pantry.reminder

import com.damy.pantry.data.CachedItem
import com.pantrykeeper.core.reminder.ItemStatus
import com.pantrykeeper.core.reminder.NoticeKind
import com.pantrykeeper.core.reminder.ReminderRules
import com.pantrykeeper.core.reminder.Tier
import kotlinx.datetime.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

data class NextAlarm(val at: ZonedDateTime, val windowMillis: Long)

object AlarmMath {
    const val WINDOW_MILLIS = 10L * 60L * 1000L

    fun nextAlarm(now: ZonedDateTime, notify: LocalTime): NextAlarm {
        var at = now.toLocalDate().atTime(notify).atZone(now.zone)
        if (!at.isAfter(now)) at = at.plusDays(1)
        return NextAlarm(at, WINDOW_MILLIS)
    }

    /** Noon catch-up: after the notify time, before 12:00, and not yet sent today. */
    fun catchUpDue(now: LocalTime, notify: LocalTime, sentToday: Boolean): Boolean {
        val noon = LocalTime.of(12, 0)
        return !sentToday && !now.isBefore(notify) && now.isBefore(noon)
    }
}

fun duePhrases(today: LocalDate, items: List<CachedItem>): List<String> {
    val lines = mutableListOf<String>()
    for (item in items) {
        if (item.status != "in_stock") continue
        val expiry = runCatching { LocalDate.parse(item.expiryDate) }.getOrNull() ?: continue
        val tiers = item.reminderTiers.mapNotNull { Tier.parse(it) }.toSet()
        val notices = ReminderRules.notices(
            today = today,
            expiry = expiry,
            tiers = tiers,
            entryDate = today,
            status = ItemStatus.IN_STOCK,
        )
        val notice = notices.firstOrNull { it.kind == NoticeKind.EXPIRY_TODAY }
            ?: notices.firstOrNull { it.kind == NoticeKind.EXPIRED }
            ?: notices.firstOrNull()
            ?: continue
        lines += ReminderRules.phrase(item.name, notice)
    }
    return lines
}

fun summaryText(today: LocalDate, items: List<CachedItem>): String =
    ReminderRules.summary(duePhrases(today, items))

fun systemZone(): ZoneId = ZoneId.systemDefault()
