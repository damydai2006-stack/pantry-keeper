package com.damy.pantry.reminder

import com.damy.pantry.data.CachedItem
import com.pantrykeeper.core.reminder.Tier
import kotlinx.datetime.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlarmMathTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun nextAlarmIsTodayBeforeTheNotifyTimeAndTomorrowAfter() {
        val morning = ZonedDateTime.of(2026, 11, 15, 8, 0, 0, 0, zone)
        val early = AlarmMath.nextAlarm(morning, LocalTime.of(9, 0))
        assertEquals(9, early.at.hour)
        assertEquals(15, early.at.dayOfMonth)
        val afternoon = ZonedDateTime.of(2026, 11, 15, 9, 0, 0, 0, zone)
        val next = AlarmMath.nextAlarm(afternoon, LocalTime.of(9, 0))
        assertEquals(16, next.at.dayOfMonth)
        assertEquals(AlarmMath.WINDOW_MILLIS, next.windowMillis)
    }

    @Test
    fun catchUpOnlyBeforeNoonWhenUnsent() {
        assertTrue(AlarmMath.catchUpDue(LocalTime.of(10, 0), LocalTime.of(9, 0), sentToday = false))
        assertFalse(AlarmMath.catchUpDue(LocalTime.of(8, 0), LocalTime.of(9, 0), sentToday = false))
        assertFalse(AlarmMath.catchUpDue(LocalTime.of(10, 0), LocalTime.of(9, 0), sentToday = true))
        assertFalse(AlarmMath.catchUpDue(LocalTime.of(12, 0), LocalTime.of(9, 0), sentToday = false))
    }

    @Test
    fun expiryDayIsTodayAndExpiredUsesPlusOnePlusSeven() {
        val today = LocalDate(2026, 11, 15)
        val dueToday = item("牛奶", "2026-11-15", listOf("D5"))
        val expiredDay1 = item("酱油", "2026-11-14", listOf("D5"))
        val expiredDay2 = item("米", "2026-11-13", emptyList())
        val phrases = duePhrases(today, listOf(dueToday, expiredDay1, expiredDay2))
        assertEquals(listOf("牛奶今天到期", "酱油已过期 1 天"), phrases)
        assertTrue(summaryText(today, listOf(dueToday, expiredDay1)).startsWith("2 件物品需要注意"))
    }

    @Test
    fun expiredEighthDayIsSentAndTheSecondDayIsNot() {
        val today = LocalDate(2026, 11, 15)
        val day8 = item("酱油", "2026-11-07", listOf("D5"))
        val day2 = item("米", "2026-11-13", emptyList())
        assertEquals(listOf("酱油已过期 8 天"), duePhrases(today, listOf(day8, day2)))
    }

    @Test
    fun summaryStopsAfterFiveNames() {
        val today = LocalDate(2026, 11, 15)
        val items = (1..6).map { item("物$it", "2026-11-15", listOf("D5")) }
        val text = summaryText(today, items)
        assertTrue(text.startsWith("6 件物品需要注意"))
        assertTrue(text.contains("等 1 件"))
    }

    @Test
    fun exactAlarmDependsOnTheGrant() {
        assertEquals(AlarmKind.EXACT, AlarmMath.alarmKind(30, canExact = false))
        assertEquals(AlarmKind.EXACT, AlarmMath.alarmKind(35, canExact = true))
        assertEquals(AlarmKind.WINDOW, AlarmMath.alarmKind(35, canExact = false))
        assertEquals(10L * 60L * 1000L, AlarmMath.WINDOW_MILLIS)
    }

    @Test
    fun missedTierIsNotSentWhenAddedToday() {
        val today = LocalDate(2026, 11, 15)
        val soon = item("面包", "2026-11-17", listOf(Tier.D30.name))
        assertTrue(duePhrases(today, listOf(soon)).isEmpty())
    }

    private fun item(name: String, expiry: String, tiers: List<String>) = CachedItem(
        id = name,
        name = name,
        category = "food",
        quantity = 1,
        expiryDate = expiry,
        reminderTiers = tiers,
        status = "in_stock",
        rev = 1,
    )
}
