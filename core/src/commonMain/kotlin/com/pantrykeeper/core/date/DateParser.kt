package com.pantrykeeper.core.date

import kotlinx.datetime.LocalDate

data class ShelfLife(val value: Int, val unit: ShelfUnit, val raw: String)

data class ParseResult(
    val resolvedExpiry: LocalDate?,
    val basis: String?,
    val needsConfirm: Boolean,
    val recognized: Boolean,
    val confident: Boolean,
    val production: LocalDate?,
    val explicitExpiry: LocalDate?,
    val shelfLife: ShelfLife?,
    val candidateDates: List<LocalDate>,
    val unlabeledDates: List<LocalDate>,
    val notes: List<String>,
)

object DateParser {
    private const val MONTH_NOTE = "包装只标到月，已按月末计算，可改"

    fun parse(text: String, today: LocalDate): ParseResult {
        val normalized = normalize(text)
        val lines = normalized.split('\n').map { it.trim() }.filter { it.isNotEmpty() && !ignoreLine(it) }
        val parsed = lines.map { parseLine(it) }
        val notes = mutableListOf<String>()

        val productions = mutableListOf<Adopted>()
        val expiries = mutableListOf<Adopted>()
        val shelves = mutableListOf<ShelfLife>()
        val unlabeled = mutableListOf<LocalDate>()

        fun addShelf(span: ShelfLifeSpan) {
            if (DateMath.shelfAllowed(span.value, span.unit)) {
                shelves += ShelfLife(span.value, span.unit, span.raw)
            } else {
                notes += "保质期超出 1 天到 10 年"
            }
        }

        parsed.forEachIndexed { index, line ->
            if (line.labels.any { it.kind == LabelKind.SHELF } &&
                line.shelves.isEmpty() &&
                (line.raw.contains("见包装") || line.raw.contains("见瓶身"))
            ) {
                notes += "保质期见包装，已忽略"
            }
            line.shelves.forEach { shelf ->
                val label = nearestKind(shelf.start, shelf.end, line.labels) ?: neighborKind(parsed, index)
                if (label == LabelKind.SHELF || line.labels.any { it.kind == LabelKind.SHELF }) addShelf(shelf)
            }
            line.dates.forEach { hit ->
                val kind = nearestKind(hit.start, hit.end, line.labels) ?: neighborKind(parsed, index)
                when (kind) {
                    LabelKind.PRODUCTION -> resolve(hit, today, asExpiry = false)?.let { productions += it }
                    LabelKind.EXPIRY -> resolve(hit, today, asExpiry = true)?.let { expiries += it }
                    LabelKind.SHELF, null -> {
                        resolve(hit, today, asExpiry = true)?.let { unlabeled += it.date }
                    }
                }
            }
        }

        var production = productions.firstOrNull()
        if (productions.size > 1) notes += "识别到多个生产日期"
        val explicit = expiries.firstOrNull()
        if (expiries.size > 1) notes += "识别到多个到期日"
        val shelf = shelves.firstOrNull()

        if (production != null && production.date > DateMath.plusDays(today, 1)) {
            notes += "生产日期晚于今天，已忽略"
            production = null
        }

        val computed = if (production != null && shelf != null && DateMath.shelfAllowed(shelf.value, shelf.unit)) {
            DateMath.addShelf(production.date, shelf.value, shelf.unit)
        } else {
            null
        }

        var resolved: LocalDate? = null
        var basis: String? = null
        var needsConfirm = false
        val candidates = mutableListOf<LocalDate>()

        when {
            explicit != null && computed != null -> {
                candidates += explicit.date
                candidates += computed
                if (explicit.date == computed) {
                    resolved = explicit.date
                    basis = "ocr_expiry"
                    needsConfirm = explicit.ambiguous || production?.ambiguous == true
                } else {
                    resolved = explicit.date
                    basis = "ocr_expiry"
                    needsConfirm = true
                    notes += "明示到期日与推算不一致，以明示为准"
                }
                if (explicit.monthOnly) notes += MONTH_NOTE
            }
            explicit != null -> {
                resolved = explicit.date
                basis = "ocr_expiry"
                needsConfirm = explicit.ambiguous
                candidates += explicit.date
                if (explicit.monthOnly) notes += MONTH_NOTE
            }
            computed != null && production != null -> {
                resolved = computed
                basis = "ocr_prod_plus_life"
                needsConfirm = production.ambiguous
                candidates += production.date
                candidates += computed
            }
        }

        if (production != null) candidates += production.date
        if (resolved != null && production != null && resolved <= production.date) {
            notes += "到期日不晚于生产日期，未识别"
            resolved = null
            basis = null
            needsConfirm = false
        }

        val distinctCandidates = candidates.distinct()
        val recognized = resolved != null
        return ParseResult(
            resolvedExpiry = resolved,
            basis = basis,
            needsConfirm = needsConfirm && recognized,
            recognized = recognized,
            confident = recognized && !needsConfirm,
            production = production?.date,
            explicitExpiry = explicit?.date,
            shelfLife = shelf,
            candidateDates = distinctCandidates,
            unlabeledDates = unlabeled.distinct(),
            notes = notes.distinct(),
        )
    }

    private data class Adopted(val date: LocalDate, val ambiguous: Boolean, val monthOnly: Boolean)

    private fun resolve(hit: RawDate, today: LocalDate, asExpiry: Boolean): Adopted? {
        val date = when (hit.kind) {
            DateKind.FULL, DateKind.MONTH_ONLY -> hit.date
            DateKind.MONTH_DAY -> {
                val month = hit.month ?: return null
                val day = hit.day ?: return null
                resolveMonthDay(month, day, today, asExpiry)
            }
        } ?: return null
        if (date.year !in DateMath.MIN_YEAR..DateMath.MAX_YEAR) return null
        return Adopted(date, hit.ambiguous, hit.kind == DateKind.MONTH_ONLY)
    }

    private fun resolveMonthDay(month: Int, day: Int, today: LocalDate, asExpiry: Boolean): LocalDate? {
        fun build(year: Int): LocalDate? =
            if (year !in DateMath.MIN_YEAR..DateMath.MAX_YEAR) null
            else runCatching { LocalDate(year, month, day) }.getOrNull()
        val thisYear = build(today.year) ?: return null
        return if (asExpiry) {
            if (thisYear >= today) thisYear else build(today.year + 1)
        } else {
            if (thisYear <= today) thisYear else build(today.year - 1)
        }
    }

    private enum class LabelKind { PRODUCTION, EXPIRY, SHELF }

    private data class LabelHit(val kind: LabelKind, val start: Int, val end: Int)

    private enum class DateKind { FULL, MONTH_ONLY, MONTH_DAY }

    private data class RawDate(
        val kind: DateKind,
        val start: Int,
        val end: Int,
        val date: LocalDate?,
        val month: Int?,
        val day: Int?,
        val ambiguous: Boolean,
    )

    private data class LineParsed(
        val raw: String,
        val labels: List<LabelHit>,
        val dates: List<RawDate>,
        val shelves: List<ShelfLifeSpan>,
    )

    private data class ShelfLifeSpan(val value: Int, val unit: ShelfUnit, val raw: String, val start: Int, val end: Int)

    private fun parseLine(line: String): LineParsed {
        val labels = findLabels(line)
        val dates = findDates(line)
        val shelves = findShelves(line)
        return LineParsed(line, labels, dates, shelves)
    }

    private fun nearestKind(start: Int, end: Int, labels: List<LabelHit>): LabelKind? {
        if (labels.isEmpty()) return null
        return labels.minWithOrNull(
            compareBy<LabelHit> { distance(start, end, it) }
                .thenBy { if (it.end <= start) 0 else 1 }
                .thenBy { it.start },
        )?.kind
    }

    private fun distance(start: Int, end: Int, label: LabelHit): Int = when {
        label.end <= start -> start - label.end
        label.start >= end -> label.start - end
        else -> 0
    }

    private fun neighborKind(lines: List<LineParsed>, index: Int): LabelKind? {
        val prev = lines.getOrNull(index - 1)
        if (prev != null && prev.dates.isEmpty() && prev.labels.isNotEmpty()) return prev.labels.last().kind
        val next = lines.getOrNull(index + 1)
        if (next != null && next.dates.isEmpty() && next.labels.isNotEmpty()) return next.labels.first().kind
        return null
    }

    private fun findLabels(line: String): List<LabelHit> {
        val lower = line.lowercase()
        val specs = listOf(
            LabelKind.EXPIRY to listOf("有效期至", "失效日期", "到期日", "限用日期", "保质期至", "有效期", "best before", "请于"),
            LabelKind.PRODUCTION to listOf("生产日期", "制造日期", "出厂日期", "生产"),
            LabelKind.SHELF to listOf("保质期限", "保质期", "保存期"),
        )
        val found = mutableListOf<LabelHit>()
        for ((kind, words) in specs) {
            for (word in words) {
                var from = 0
                while (from < lower.length) {
                    val at = lower.indexOf(word, from)
                    if (at < 0) break
                    found += LabelHit(kind, at, at + word.length)
                    from = at + word.length
                }
            }
        }
        latinLabels(line).forEach { found += it }
        return nonOverlapping(found)
    }

    private fun latinLabels(line: String): List<LabelHit> {
        val patterns = listOf(
            LabelKind.PRODUCTION to Regex("(?i)(?<![A-Za-z])(?:MFG|MFD|PD)(?![A-Za-z])"),
            LabelKind.EXPIRY to Regex("(?i)(?<![A-Za-z])(?:EXP|BB)(?![A-Za-z])"),
        )
        return patterns.flatMap { (kind, regex) ->
            regex.findAll(line).map { LabelHit(kind, it.range.first, it.range.last + 1) }
        }
    }

    private fun nonOverlapping(found: List<LabelHit>): List<LabelHit> {
        val sorted = found.sortedWith(compareBy<LabelHit> { it.start }.thenByDescending { it.end - it.start })
        val kept = mutableListOf<LabelHit>()
        for (hit in sorted) {
            if (kept.any { overlaps(it.start, it.end, hit.start, hit.end) }) continue
            kept += hit
        }
        return kept.sortedBy { it.start }
    }

    private fun overlaps(a0: Int, a1: Int, b0: Int, b1: Int): Boolean = a0 < b1 && b0 < a1

    private fun findShelves(line: String): List<ShelfLifeSpan> {
        val regex = Regex("(?i)(\\d+)\\s*(个月|months|month|天|日|days|day|年|years|year|月)")
        return regex.findAll(line).mapNotNull { m ->
            val value = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val unit = when (m.groupValues[2].lowercase()) {
                "天", "日", "day", "days" -> ShelfUnit.DAY
                "月", "个月", "month", "months" -> ShelfUnit.MONTH
                "年", "year", "years" -> ShelfUnit.YEAR
                else -> return@mapNotNull null
            }
            ShelfLifeSpan(value, unit, m.value, m.range.first, m.range.last + 1)
        }.toList()
    }

    private fun findDates(line: String): List<RawDate> {
        val consumed = BooleanArray(line.length)
        val hits = mutableListOf<RawDate>()
        fun take(range: IntRange, build: () -> RawDate?) {
            if (range.isEmpty() || range.any { it !in consumed.indices || consumed[it] }) return
            val hit = build() ?: return
            range.forEach { consumed[it] = true }
            hits += hit
        }
        val ymdCn = Regex("(\\d{4})年(\\d{1,2})月(\\d{1,2})日")
        val ymd = Regex("(\\d{4})[./-](\\d{1,2})[./-](\\d{1,2})(?!\\d)")
        val mdy = Regex("(?<!\\d)(\\d{1,2})[./-](\\d{1,2})[./-](\\d{4})(?!\\d)")
        val yymd = Regex("(?<!\\d)(\\d{2})[./-](\\d{1,2})[./-](\\d{1,2})(?!\\d)")
        val compact = Regex("(?<!\\d)(20\\d{6})(?!\\d)")
        val ymCn = Regex("(\\d{4})年(\\d{1,2})月(?!\\d)")
        val ym = Regex("(\\d{4})[./-](\\d{1,2})(?!\\d)(?![./-]\\d)")
        val my = Regex("(?<!\\d)(\\d{1,2})[./-](\\d{4})(?!\\d)")
        val md = Regex("(?<!\\d)(\\d{1,2})[./-](\\d{1,2})(?!\\d)")

        for (m in ymdCn.findAll(line)) {
            take(m.range) { fullDate(m.range, m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), ambiguous = false) }
        }
        for (m in ymd.findAll(line)) {
            take(m.range) { fullDate(m.range, m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), ambiguous = false) }
        }
        for (m in mdy.findAll(line)) {
            take(m.range) { fullDate(m.range, m.groupValues[3].toInt(), m.groupValues[1].toInt(), m.groupValues[2].toInt(), ambiguous = true) }
        }
        for (m in yymd.findAll(line)) {
            val yy = m.groupValues[1].toInt()
            if (yy !in 0..45) continue
            take(m.range) { fullDate(m.range, 2000 + yy, m.groupValues[2].toInt(), m.groupValues[3].toInt(), ambiguous = true) }
        }
        for (m in compact.findAll(line)) {
            val raw = m.groupValues[1]
            take(m.range) {
                fullDate(m.range, raw.substring(0, 4).toInt(), raw.substring(4, 6).toInt(), raw.substring(6, 8).toInt(), ambiguous = false)
            }
        }
        for (m in ymCn.findAll(line)) {
            take(m.range) { monthOnly(m.range, m.groupValues[1].toInt(), m.groupValues[2].toInt()) }
        }
        for (m in ym.findAll(line)) {
            take(m.range) { monthOnly(m.range, m.groupValues[1].toInt(), m.groupValues[2].toInt()) }
        }
        for (m in my.findAll(line)) {
            take(m.range) { monthOnly(m.range, m.groupValues[2].toInt(), m.groupValues[1].toInt()) }
        }
        for (m in md.findAll(line)) {
            val month = m.groupValues[1].toInt()
            val day = m.groupValues[2].toInt()
            if (month !in 1..12 || day !in 1..31) continue
            take(m.range) {
                RawDate(DateKind.MONTH_DAY, m.range.first, m.range.last + 1, null, month, day, ambiguous = true)
            }
        }
        return hits.sortedBy { it.start }
    }

    private fun fullDate(range: IntRange, year: Int, month: Int, day: Int, ambiguous: Boolean): RawDate? {
        if (year !in DateMath.MIN_YEAR..DateMath.MAX_YEAR || month !in 1..12) return null
        val date = runCatching { LocalDate(year, month, day) }.getOrNull() ?: return null
        return RawDate(DateKind.FULL, range.first, range.last + 1, date, month, day, ambiguous)
    }

    private fun monthOnly(range: IntRange, year: Int, month: Int): RawDate? {
        if (year !in DateMath.MIN_YEAR..DateMath.MAX_YEAR || month !in 1..12) return null
        val date = LocalDate(year, month, DateMath.lastDayOfMonth(year, month))
        return RawDate(DateKind.MONTH_ONLY, range.first, range.last + 1, date, month, date.dayOfMonth, ambiguous = true)
    }

    private fun ignoreLine(line: String): Boolean {
        if (line.contains("批号") || line.contains("批次") || line.contains("批准文号") || line.contains("国药准字")) {
            return true
        }
        return Regex("(?i)(?<![A-Za-z])lot(?![A-Za-z])").containsMatchIn(line)
    }

    fun normalize(text: String): String {
        var s = fullwidthToHalf(text).replace("\r\n", "\n").replace('\r', '\n')
        val traditional = listOf(
            "生產日期" to "生产日期",
            "出廠日期" to "出厂日期",
            "製造日期" to "制造日期",
            "保質期至" to "保质期至",
            "保質期限" to "保质期限",
            "保質期" to "保质期",
            "生產批號" to "生产批号",
            "產品批號" to "产品批号",
            "批號" to "批号",
            "國藥準字" to "国药准字",
            "生產" to "生产",
            "保質" to "保质",
            "製造" to "制造",
            "出廠" to "出厂",
            "個" to "个",
        )
        for ((from, to) in traditional) s = s.replace(from, to)
        s = s.replace("半年", "6个月")
        s = replaceChineseNumbers(s)
        val gap = Regex("([0-9OoIl./\\-年月日])\\s+(?=[0-9OoIl./\\-年月日])")
        while (true) {
            val next = gap.replace(s, "$1")
            if (next == s) break
            s = next
        }
        s = Regex("[0-9OoIl]+").replace(s) { token ->
            val value = token.value
            if (value.length >= 2 && value.any { it.isDigit() }) {
                value.map { ch ->
                    when (ch) {
                        'O', 'o' -> '0'
                        'l', 'I' -> '1'
                        else -> ch
                    }
                }.joinToString("")
            } else {
                value
            }
        }
        return s
    }

    private fun replaceChineseNumbers(input: String): String {
        val regex = Regex("([零一二两三四五六七八九十百千]+)\\s*(个)?\\s*(天|日|月|年)")
        return regex.replace(input) { m ->
            val n = chineseNumber(m.groupValues[1]) ?: return@replace m.value
            val ge = m.groupValues[2]
            val unit = m.groupValues[3]
            "$n$ge$unit"
        }
    }

    private fun chineseNumber(raw: String): Int? {
        if (raw.isEmpty()) return null
        val digit = mapOf(
            '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
        )
        var total = 0
        var current = 0
        var seen = false
        for (ch in raw) {
            when {
                ch in digit -> {
                    current = digit.getValue(ch)
                    seen = true
                }
                ch == '十' || ch == '百' || ch == '千' -> {
                    val factor = when (ch) {
                        '十' -> 10
                        '百' -> 100
                        else -> 1000
                    }
                    total += (if (current == 0) 1 else current) * factor
                    current = 0
                    seen = true
                }
                else -> return null
            }
        }
        return if (seen) total + current else null
    }

    private fun fullwidthToHalf(raw: String): String = buildString(raw.length) {
        for (ch in raw) {
            when (ch) {
                in '\uFF01'..'\uFF5E' -> append((ch.code - 0xFEE0).toChar())
                '　' -> append(' ')
                else -> append(ch)
            }
        }
    }
}
