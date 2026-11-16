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
    fun catchUpRunsAfterNotifyUntilTheDayEnds() {
        val morning = ZonedDateTime.of(2026, 11, 15, 8, 0, 0, 0, zone)
        val before = AlarmMath.nextCatchUp(morning, LocalTime.of(9, 0), sentToday = false)
        assertEquals(9, before.hour)
        assertEquals(15, before.minute)
        assertEquals(15, before.dayOfMonth)
        val lateMorning = ZonedDateTime.of(2026, 11, 15, 10, 0, 0, 0, zone)
        val soon = AlarmMath.nextCatchUp(lateMorning, LocalTime.of(9, 0), sentToday = false)
        assertEquals(lateMorning, soon)
        val evening = ZonedDateTime.of(2026, 11, 15, 19, 0, 0, 0, zone)
        val afternoon = AlarmMath.nextCatchUp(evening, LocalTime.of(18, 0), sentToday = false)
        assertEquals(evening, afternoon)
        val almostMidnight = ZonedDateTime.of(2026, 11, 15, 23, 50, 0, 0, zone)
        assertTrue(AlarmMath.stillTimeToCatchUp(almostMidnight, LocalTime.of(21, 0), sentToday = false))
        val sent = AlarmMath.nextCatchUp(lateMorning, LocalTime.of(9, 0), sentToday = true)
        assertEquals(16, sent.dayOfMonth)
        assertEquals(9, sent.hour)
        val afterMidnight = ZonedDateTime.of(2026, 11, 16, 0, 10, 0, 0, zone)
        assertFalse(AlarmMath.stillTimeToCatchUp(afterMidnight, LocalTime.of(21, 0), sentToday = false))
        val nextDay = AlarmMath.nextCatchUp(afterMidnight, LocalTime.of(21, 0), sentToday = false)
        assertEquals(16, nextDay.dayOfMonth)
        assertEquals(21, nextDay.hour)
    }

    @Test
    fun expiryDayIsTodayAndExpiredUsesPlusOnePlusSeven() {
        val today = LocalDate(2026, 11, 15)
        val dueToday = item("牛奶", "2026-11-15", listOf("D5"))
        val expiredDay1 = item("酱油", "2026-11-14", listOf("D5"))
        val expiredDay2 = item("米", "2026-11-13", emptyList())
        val phrases = duePhrases(today, listOf(dueToday, expiredDay1, expiredDay2))
        assertEquals(listOf("牛奶今天到期", "酱油已过期 1 天"), phrases)
        assertEquals("1 件已过期，1 件将在 7 天内到期", summaryText(today, listOf(dueToday, expiredDay1)))
    }

    @Test
    fun expiredEighthDayIsSentAndTheSecondDayIsNot() {
        val today = LocalDate(2026, 11, 15)
        val day8 = item("酱油", "2026-11-07", listOf("D5"))
        val day2 = item("米", "2026-11-13", emptyList())
        assertEquals(listOf("酱油已过期 8 天"), duePhrases(today, listOf(day8, day2)))
    }

    @Test
    fun summaryCountsGroupsAndSkipsNames() {
        val today = LocalDate(2026, 11, 15)
        val items = (1..6).map { item("物$it", "2026-11-15", listOf("D5")) }
        assertEquals("6 件将在 7 天内到期", summaryText(today, items))
        assertFalse(summaryText(today, items).contains("物1"))
    }

    @Test
    fun openedMedicineUsesTheEarlierDate() {
        val today = LocalDate(2026, 11, 15)
        val opened = item("感冒灵", "2026-12-01", listOf("D5")).copy(
            category = "medicine",
            openedDate = "2026-11-10",
            afterOpenDays = 5,
        )
        val sealed = opened.copy(openedDate = null, afterOpenDays = null)
        assertEquals(listOf("感冒灵今天到期"), duePhrases(today, listOf(opened)))
        assertTrue(duePhrases(today, listOf(sealed)).isEmpty())
    }

    @Test
    fun notifyTimeFormatsHourAndMinute() {
        assertEquals("08:30", formatNotifyTime(8, 30))
        assertEquals("21:05", formatNotifyTime(21, 5))
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
