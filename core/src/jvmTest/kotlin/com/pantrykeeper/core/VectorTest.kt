package com.pantrykeeper.core

import com.pantrykeeper.core.date.DateMath
import com.pantrykeeper.core.date.DateParser
import com.pantrykeeper.core.date.ShelfUnit
import com.pantrykeeper.core.model.FieldLimits
import com.pantrykeeper.core.reminder.ExpiryColor
import com.pantrykeeper.core.reminder.ExpiryGroup
import com.pantrykeeper.core.reminder.ItemStatus
import com.pantrykeeper.core.reminder.NoticeKind
import com.pantrykeeper.core.reminder.ReminderRules
import com.pantrykeeper.core.reminder.Tier
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VectorTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val root: JsonObject by lazy {
        val dir = File(System.getProperty("testdata.dir") ?: error("testdata.dir"))
        json.parseToJsonElement(File(dir, "date-cases.json").readText()).jsonObject
    }

    @Test
    fun dateMathVectors() {
        for (row in root.getValue("dateMath").jsonArray.map { it.jsonObject }) {
            val id = row.str("id")
            val start = LocalDate.parse(row.str("start"))
            val amount = row.int("amount")
            val expect = LocalDate.parse(row.str("expect"))
            val actual = when (row.str("op")) {
                "plusDays" -> DateMath.plusDays(start, amount)
                "plusMonths" -> DateMath.plusMonths(start, amount)
                "plusYears" -> DateMath.plusYears(start, amount)
                else -> error("$id unknown op")
            }
            assertEquals(expect, actual, id)
        }
    }

    @Test
    fun shelfLifeVectors() {
        for (row in root.getValue("shelfLife").jsonArray.map { it.jsonObject }) {
            val id = row.str("id")
            val unit = row.str("unit").toUnit()
            val value = row.int("value")
            val allowed = DateMath.shelfAllowed(value, unit)
            assertEquals(row.bool("valid"), allowed, id)
            if (allowed && row.containsKey("start")) {
                val actual = DateMath.addShelf(LocalDate.parse(row.str("start")), value, unit)
                assertEquals(LocalDate.parse(row.str("expect")), actual, id)
            }
        }
    }

    @Test
    fun groupVectors() {
        for (row in root.getValue("groups").jsonArray.map { it.jsonObject }) {
            val id = row.str("id")
            val today = LocalDate.parse(row.str("today"))
            val expiry = LocalDate.parse(row.str("expiry"))
            assertEquals(row.int("days"), ReminderRules.daysRemaining(today, expiry), id)
            val group = ReminderRules.groupOf(today, expiry)
            assertEquals(ExpiryGroup.valueOf(row.str("group")), group, id)
            assertEquals(ExpiryColor.valueOf(row.str("color")), ReminderRules.colorOf(group), id)
        }
    }

    @Test
    fun noticeVectors() {
        for (row in root.getValue("notices").jsonArray.map { it.jsonObject }) {
            val id = row.str("id")
            val tiers = row.getValue("tiers").jsonArray.map { Tier.parse(it.jsonPrimitive.content) ?: error(id) }.toSet()
            val status = row["status"]?.jsonPrimitive?.contentOrNull?.let { ItemStatus.valueOf(it) } ?: ItemStatus.IN_STOCK
            val opened = row["opened"]?.jsonPrimitive?.contentOrNull?.let { LocalDate.parse(it) }
            val after = row["afterOpenDays"]?.jsonPrimitive?.int
            val notices = ReminderRules.notices(
                today = LocalDate.parse(row.str("today")),
                expiry = LocalDate.parse(row.str("expiry")),
                tiers = tiers,
                entryDate = LocalDate.parse(row.str("entry")),
                status = status,
                openedDate = opened,
                afterOpenDays = after,
            )
            val actual = notices.map { notice ->
                when (notice.kind) {
                    NoticeKind.TIER -> "TIER:${notice.tier}"
                    NoticeKind.EXPIRY_TODAY -> "EXPIRY_TODAY"
                    NoticeKind.EXPIRED -> "EXPIRED:${notice.expiredDays}"
                }
            }.sorted()
            val expect = row.getValue("expect").jsonArray.map { it.jsonPrimitive.content }.sorted()
            assertEquals(expect, actual, id)
        }
        assertEquals(setOf(Tier.D30, Tier.D5), Tier.DEFAULT)
    }

    @Test
    fun parserVectors() {
        val dir = File(System.getProperty("testdata.dir"))
        for (row in root.getValue("parser").jsonArray.map { it.jsonObject }) {
            val id = row.str("id")
            val text = row["text"]?.jsonPrimitive?.contentOrNull
                ?: File(dir, row.str("file")).readText()
            val result = DateParser.parse(text, LocalDate.parse(row.str("today")))
            val resolved = row["resolved"]?.jsonPrimitive?.contentOrNull
            if (resolved == null) assertNull(result.resolvedExpiry, id) else {
                assertEquals(LocalDate.parse(resolved), result.resolvedExpiry, id)
            }
            assertEquals(row.bool("recognized"), result.recognized, id)
            assertEquals(row.bool("confident"), result.confident, id)
            if (row.containsKey("needsConfirm")) assertEquals(row.bool("needsConfirm"), result.needsConfirm, id)
            if (row.containsKey("basis")) {
                val basis = row["basis"]?.jsonPrimitive?.contentOrNull
                assertEquals(basis, result.basis, id)
            }
            row["production"]?.jsonPrimitive?.contentOrNull?.let {
                assertEquals(LocalDate.parse(it), result.production, id)
            }
            row["noteContains"]?.jsonPrimitive?.contentOrNull?.let { needle ->
                assertTrue(result.notes.any { it.contains(needle) }, "$id notes=${result.notes}")
            }
            val forbidden = row["forbidDates"]?.jsonArray?.map { LocalDate.parse(it.jsonPrimitive.content) }.orEmpty()
            val seen = result.candidateDates + result.unlabeledDates + listOfNotNull(result.resolvedExpiry, result.production, result.explicitExpiry)
            forbidden.forEach { date ->
                assertTrue(date !in seen, "$id leaked $date")
            }
            if (row.boolOrNull("noCandidates") == true) {
                assertTrue(result.candidateDates.isEmpty() && result.unlabeledDates.isEmpty() && result.resolvedExpiry == null, id)
            }
        }
    }

    @Test
    fun fieldLimits() {
        assertNull(FieldLimits.familyNameError("家庭"))
        assertTrue(FieldLimits.familyNameError("家") != null)
        assertTrue(FieldLimits.familyNameError("家".repeat(21)) != null)
        assertNull(FieldLimits.itemNameError("牛奶"))
        assertTrue(FieldLimits.itemNameError("物".repeat(41)) != null)
        assertNull(FieldLimits.noteError(null))
        assertTrue(FieldLimits.noteError("注".repeat(201)) != null)
        assertNull(FieldLimits.locationError("冰箱"))
        assertTrue(FieldLimits.locationError("位".repeat(13)) != null)
    }
}

private fun JsonObject.str(name: String): String = getValue(name).jsonPrimitive.content

private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int

private fun JsonObject.bool(name: String): Boolean = getValue(name).jsonPrimitive.boolean

private fun JsonObject.boolOrNull(name: String): Boolean? = this[name]?.jsonPrimitive?.boolean

private fun String.toUnit(): ShelfUnit = when (this) {
    "day" -> ShelfUnit.DAY
    "month" -> ShelfUnit.MONTH
    "year" -> ShelfUnit.YEAR
    else -> error(this)
}
