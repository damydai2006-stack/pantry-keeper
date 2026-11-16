package com.damy.pantry.capture

import com.damy.pantry.data.PendingPhoto
import com.pantrykeeper.core.date.DateParser
import com.pantrykeeper.core.date.ShelfLife
import kotlinx.datetime.LocalDate

sealed interface OcrView {
    data class Confirm(
        val expiry: LocalDate,
        val production: LocalDate?,
        val shelf: ShelfLife?,
        val yellow: Boolean,
        val notes: List<String>,
        val alternate: LocalDate?,
        val basis: String?,
    ) : OcrView

    data class Manual(val raw: String) : OcrView
}

object OcrPipeline {
    fun interpret(text: String, today: LocalDate): OcrView {
        val normalized = DateParser.normalize(text)
        val parsed = runCatching { DateParser.parse(text, today) }.getOrNull()
            ?: return OcrView.Manual(text)
        val expiry = parsed.resolvedExpiry ?: return OcrView.Manual(text)
        if (!parsed.recognized) return OcrView.Manual(text)
        return OcrView.Confirm(
            expiry = expiry,
            production = parsed.production,
            shelf = parsed.shelfLife,
            yellow = parsed.needsConfirm || !parsed.confident,
            notes = parsed.notes,
            alternate = dayMonthSwap(normalized, expiry),
            basis = parsed.basis,
        )
    }

    /** MM/DD/YYYY printed dates keep the other order so the confirm page can offer it. */
    fun dayMonthSwap(text: String, chosen: LocalDate): LocalDate? {
        val match = Regex("""(\d{1,2})[./](\d{1,2})[./](\d{4})""").find(text) ?: return null
        val first = match.groupValues[1].toInt()
        val second = match.groupValues[2].toInt()
        val year = match.groupValues[3].toInt()
        val swapped = runCatching { LocalDate(year, second, first) }.getOrNull() ?: return null
        return swapped.takeIf { it != chosen }
    }
}

data class SampleExpectation(
    val id: String,
    val clear: Boolean,
    val expiry: String?,
    val yellow: Boolean,
)

/** Answers from the Q-16 sample README. Unclear rows must not be a confident auto date. */
val OCR_SAMPLE_EXPECTATIONS = listOf(
    SampleExpectation("01-clear-yyyy-mm-dd", true, "2027-03-15", false),
    SampleExpectation("02-clear-yyyy.mm.dd", true, "2027-06-30", false),
    SampleExpectation("03-clear-prod-plus-shelf", true, "2027-11-15", false),
    SampleExpectation("04-clear-exp-03-2027", true, "2027-03-31", true),
    SampleExpectation("05-clear-ambiguous-03-04-2027", true, "2027-03-04", true),
    SampleExpectation("06-unclear-blur", false, null, false),
    SampleExpectation("07-unclear-low-contrast", false, null, false),
    SampleExpectation("08-unclear-perspective", false, null, false),
    SampleExpectation("09-unclear-occlusion", false, null, false),
    SampleExpectation("10-unclear-dot-matrix", false, null, false),
)

object PendingCapture {
    var prefill: CapturePrefill? = null
    var jpeg: ByteArray?
        get() = PendingPhoto.jpeg
        set(value) { PendingPhoto.jpeg = value }
}

data class SampleHit(val id: String, val clear: Boolean, val hit: Boolean, val detail: String)

fun scoreSample(text: String, today: LocalDate, expected: SampleExpectation): SampleHit {
    val view = OcrPipeline.interpret(text, today)
    val hit = when (view) {
        is OcrView.Manual -> !expected.clear
        is OcrView.Confirm -> if (!expected.clear) {
            false
        } else {
            view.expiry.toString() == expected.expiry && view.yellow == expected.yellow
        }
    }
    val detail = when (view) {
        is OcrView.Manual -> "manual"
        is OcrView.Confirm -> "expiry=${view.expiry} yellow=${view.yellow} basis=${view.basis} alt=${view.alternate}"
    }
    return SampleHit(expected.id, expected.clear, hit, detail)
}
