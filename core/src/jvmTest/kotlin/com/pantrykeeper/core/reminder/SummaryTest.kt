package com.pantrykeeper.core.reminder

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SummaryTest {
    @Test
    fun documentedSummaryWording() {
        val today = LocalDate.parse("2026-11-15")
        val milk = ReminderRules.notices(
            today = today,
            expiry = today,
            tiers = setOf(Tier.D5),
            entryDate = LocalDate.parse("2026-11-01"),
        ).single { it.kind == NoticeKind.EXPIRY_TODAY }
        val cold = ReminderRules.notices(
            today = today,
            expiry = LocalDate.parse("2026-11-20"),
            tiers = setOf(Tier.D5),
            entryDate = LocalDate.parse("2026-11-01"),
        ).single { it.kind == NoticeKind.TIER }
        val sauce = ReminderRules.notices(
            today = LocalDate.parse("2026-11-23"),
            expiry = LocalDate.parse("2026-11-15"),
            tiers = setOf(Tier.D5),
            entryDate = LocalDate.parse("2026-11-01"),
        ).single { it.kind == NoticeKind.EXPIRED }
        assertEquals("牛奶今天到期", ReminderRules.phrase("牛奶", milk))
        assertEquals("感冒灵 5 天后到期", ReminderRules.phrase("感冒灵", cold))
        assertEquals(8, sauce.expiredDays)
        assertEquals("酱油已过期 8 天", ReminderRules.phrase("酱油", sauce))
        assertEquals(
            "3 件物品需要注意：牛奶今天到期；感冒灵 5 天后到期；酱油已过期 8 天",
            ReminderRules.summary(
                listOf(
                    ReminderRules.phrase("牛奶", milk),
                    ReminderRules.phrase("感冒灵", cold),
                    ReminderRules.phrase("酱油", sauce),
                ),
            ),
        )
    }

    @Test
    fun summaryCapsAtFiveNames() {
        val lines = listOf("甲今天到期", "乙今天到期", "丙今天到期", "丁今天到期", "戊今天到期", "己今天到期")
        val text = ReminderRules.summary(lines)
        assertEquals("6 件物品需要注意：甲今天到期；乙今天到期；丙今天到期；丁今天到期；戊今天到期等 1 件", text)
        assertTrue("己" !in text)
    }

    @Test
    fun emptyTiersDoNotNotifyButStillGroup() {
        val today = LocalDate.parse("2026-11-15")
        val expiry = LocalDate.parse("2026-11-15")
        assertTrue(
            ReminderRules.notices(today, expiry, emptySet(), LocalDate.parse("2026-11-01")).isEmpty(),
        )
        assertEquals(ExpiryGroup.WITHIN_7, ReminderRules.groupOf(today, expiry))
        assertEquals(ExpiryColor.ORANGE, ReminderRules.colorOf(ExpiryGroup.WITHIN_7))
    }
}
