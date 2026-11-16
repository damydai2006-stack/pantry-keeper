package com.pantrykeeper.core.date

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DateParserEdgeTest {
    private val today = LocalDate(2026, 11, 15)

    @Test
    fun quantityUnitsAndACutOffYearStayManual() {
        val text = """
            钙片咀嚼片
            60 片 · 药品
            1.5 g
            片
            有效期至
            2027-
            特价
            置于干燥处
        """.trimIndent()
        val parsed = DateParser.parse(text, today)
        assertFalse(parsed.recognized)
        assertNull(parsed.resolvedExpiry)
    }

    @Test
    fun spacedLabelAcceptsTheDateOnTheNextLine() {
        val parsed = DateParser.parse("保 质 期 至\n2027-03-15", today)
        assertEquals(LocalDate(2027, 3, 15), parsed.resolvedExpiry)
        assertTrue(parsed.confident)
    }

    @Test
    fun whitespaceInsideADateDoesNotCrossLines() {
        val parsed = DateParser.parse("2027-\n03-15", today)
        assertFalse(parsed.recognized)
    }

    @Test
    fun truncatedDayIsYellow() {
        val parsed = DateParser.parse("有效期至 2027-08-2", today)
        assertEquals(LocalDate(2027, 8, 2), parsed.resolvedExpiry)
        assertTrue(parsed.needsConfirm)
        assertFalse(parsed.confident)
    }

    @Test
    fun monthDayWithoutALabelOnTheSameLineIsIgnored() {
        val parsed = DateParser.parse("1.5\n有效期至", today)
        assertFalse(parsed.recognized)
    }
}
