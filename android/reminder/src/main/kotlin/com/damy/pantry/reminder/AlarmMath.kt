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

enum class AlarmKind { EXACT, WINDOW }

object AlarmMath {
    const val WINDOW_MILLIS = 10L * 60L * 1000L

    /** Exact alarms need the user grant from Android 12. Otherwise use the 10 minute window. */
    fun alarmKind(sdkInt: Int, canExact: Boolean): AlarmKind =
        if (sdkInt < 31 || canExact) AlarmKind.EXACT else AlarmKind.WINDOW

    fun nextAlarm(now: ZonedDateTime, notify: LocalTime): NextAlarm {
        var at = now.toLocalDate().atTime(notify).atZone(now.zone)
        if (!at.isAfter(now)) at = at.plusDays(1)
        return NextAlarm(at, WINDOW_MILLIS)
    }

    /**
     * One catch-up after the reminder time, any time before the local day ends.
     * Morning and afternoon notify times use the same rule. After midnight the
     * missed day is not backfilled; the next slot is that new day's notify time.
     */
    const val CATCH_UP_AFTER_MINUTES = 15L

    /** Notify + 15 minutes, but never later than 23:59 on that same local day. */
    fun catchUpSlot(day: java.time.LocalDate, notify: LocalTime, zone: ZoneId): ZonedDateTime {
        val raw = day.atTime(notify).plusMinutes(CATCH_UP_AFTER_MINUTES).atZone(zone)
        val latest = day.atTime(23, 59).atZone(zone)
        return if (raw.toLocalDate() == day && !raw.isAfter(latest)) raw else latest
    }

    /**
     * 23:59 is the latest catch-up slot, so a reminder at that time would fire the
     * alarm and the catch-up together. Do not schedule a catch-up in that case.
     */
    fun schedulesCatchUp(notify: LocalTime): Boolean = notify.isBefore(LocalTime.of(23, 59))

    fun nextCatchUp(now: ZonedDateTime, notify: LocalTime, sentToday: Boolean): ZonedDateTime {
        if (!schedulesCatchUp(notify)) {
            return catchUpSlot(now.toLocalDate().plusDays(1), notify, now.zone)
        }
        val today = now.toLocalDate()
        val slot = catchUpSlot(today, notify, now.zone)
        val endOfDay = today.plusDays(1).atStartOfDay(now.zone)
        if (!sentToday && now.isBefore(endOfDay)) {
            return if (now.isBefore(slot)) slot else now
        }
        return catchUpSlot(today.plusDays(1), notify, now.zone)
    }

    fun stillTimeToCatchUp(now: ZonedDateTime, notify: LocalTime, sentToday: Boolean): Boolean {
        if (sentToday) return false
        val notifyAt = now.toLocalDate().atTime(notify).atZone(now.zone)
        val endOfDay = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
        return !now.isBefore(notifyAt) && now.isBefore(endOfDay)
    }
}

/** Bottom navigation height from the design grid, plus the extra clearance under a scrolling list. */
const val BOTTOM_TAB_DP = 72
const val LIST_CLEARANCE_DP = 16

/** First system notification prompt is preceded by the in-app explanation. */
fun shouldExplainNotification(sdk: Int, allowed: Boolean, prompted: Boolean): Boolean =
    sdk >= 33 && !allowed && !prompted

enum class NotificationFollowUp { NONE, EXPLAIN, SETTINGS }

/** Same path as the home banner: explain once, otherwise open system settings. */
fun notificationFollowUp(sdk: Int, allowed: Boolean, prompted: Boolean): NotificationFollowUp {
    if (sdk < 33 || allowed) return NotificationFollowUp.NONE
    return if (shouldExplainNotification(sdk, allowed, prompted)) {
        NotificationFollowUp.EXPLAIN
    } else {
        NotificationFollowUp.SETTINGS
    }
}

/** Check, send, and mark share one lock so the alarm and a catch-up cannot both notify. */
class SummaryOnce {
    private val lock = Any()

    fun claim(alreadySent: () -> Boolean, send: () -> Unit, mark: () -> Unit): Boolean {
        synchronized(lock) {
            if (alreadySent()) return false
            send()
            mark()
            return true
        }
    }
}

/** Reschedule even when sending throws, so one failure does not drop later alarms. */
fun finishDelivery(send: () -> Unit, reschedule: () -> Unit) {
    try {
        send()
    } finally {
        reschedule()
    }
}

private const val NOTIF_PROMPTED = "notif_prompted"

fun notificationPrompted(context: android.content.Context): Boolean =
    context.getSharedPreferences("pantry-reminder", android.content.Context.MODE_PRIVATE)
        .getBoolean(NOTIF_PROMPTED, false)

fun markNotificationPrompted(context: android.content.Context) {
    context.getSharedPreferences("pantry-reminder", android.content.Context.MODE_PRIVATE)
        .edit().putBoolean(NOTIF_PROMPTED, true).apply()
}

fun formatNotifyTime(hour: Int, minute: Int): String =
    String.format(java.util.Locale.ROOT, "%02d:%02d", hour, minute)

fun duePhrases(today: LocalDate, items: List<CachedItem>): List<String> {
    val ranked = mutableListOf<Pair<Int, String>>()
    for (item in items) {
        if (item.status != "in_stock") continue
        val expiry = runCatching { LocalDate.parse(item.expiryDate) }.getOrNull() ?: continue
        val opened = item.openedDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val tiers = item.reminderTiers.mapNotNull { Tier.parse(it) }.toSet()
        val notices = ReminderRules.notices(
            today = today,
            expiry = expiry,
            tiers = tiers,
            entryDate = LocalDate(2000, 1, 1),
            status = ItemStatus.IN_STOCK,
            openedDate = opened,
            afterOpenDays = item.afterOpenDays,
        )
        val notice = notices.firstOrNull { it.kind == NoticeKind.EXPIRY_TODAY }
            ?: notices.firstOrNull { it.kind == NoticeKind.EXPIRED }
            ?: notices.firstOrNull()
            ?: continue
        val rank = when (notice.kind) {
            NoticeKind.EXPIRY_TODAY -> 0
            NoticeKind.TIER -> 1
            NoticeKind.EXPIRED -> 2
        }
        ranked += rank to ReminderRules.phrase(item.name, notice)
    }
    return ranked.sortedBy { it.first }.map { it.second }
}

fun deviceToday(): LocalDate {
    val date = java.time.LocalDate.now(ZoneId.systemDefault())
    return LocalDate(date.year, date.monthValue, date.dayOfMonth)
}

fun summaryText(today: LocalDate, items: List<CachedItem>): String {
    var expired = 0
    var week = 0
    var month = 0
    var later = 0
    for (item in items) {
        if (item.status != "in_stock") continue
        val expiry = effectiveReminderDate(item) ?: continue
        val tiers = item.reminderTiers.mapNotNull { Tier.parse(it) }.toSet()
        val opened = item.openedDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val notices = ReminderRules.notices(
            today = today,
            expiry = runCatching { LocalDate.parse(item.expiryDate) }.getOrNull() ?: continue,
            tiers = tiers,
            entryDate = LocalDate(2000, 1, 1),
            status = ItemStatus.IN_STOCK,
            openedDate = opened,
            afterOpenDays = item.afterOpenDays,
        )
        if (notices.isEmpty()) continue
        val days = ReminderRules.daysRemaining(today, expiry)
        when {
            days < 0 -> expired += 1
            days <= 7 -> week += 1
            days <= 30 -> month += 1
            else -> later += 1
        }
    }
    return buildList {
        if (expired > 0) add("$expired 件已过期")
        if (week > 0) add("$week 件将在 7 天内到期")
        if (month > 0) add("$month 件将在 8–30 天内到期")
        if (later > 0) add("$later 件将在 30 天以后到期")
    }.joinToString("，")
}

fun effectiveReminderDate(item: CachedItem): LocalDate? {
    val expiry = runCatching { LocalDate.parse(item.expiryDate) }.getOrNull() ?: return null
    val opened = item.openedDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    return ReminderRules.effectiveExpiry(expiry, opened, item.afterOpenDays)
}

fun systemZone(): ZoneId = ZoneId.systemDefault()
