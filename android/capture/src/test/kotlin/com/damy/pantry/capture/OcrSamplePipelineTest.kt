package com.damy.pantry.capture

import kotlinx.datetime.LocalDate
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OcrSamplePipelineTest {
    private val today = LocalDate(2026, 11, 15)

    @Test
    fun q16TextFixturesHitRate() {
        val root = Path.of(System.getProperty("repo.root") ?: error("repo.root"))
        val hits = OCR_SAMPLE_EXPECTATIONS.map { expected ->
            val text = root.resolve("testdata/ocr/${expected.id}.txt").toFile().readText()
            scoreSample(text, today, expected)
        }
        hits.forEach { println("${it.id}\tclear=${it.clear}\thit=${it.hit}\t${it.detail}") }
        val clearHits = hits.count { it.clear && it.hit }
        val unclearSafe = hits.count { !it.clear && it.hit }
        println("clear $clearHits/5 unclear-fallback $unclearSafe/5")
        assertTrue(clearHits >= 4, "clear samples need at least 4 hits, got $clearHits")
        assertEquals(5, unclearSafe)
        assertEquals(10, hits.size)
    }

    @Test
    fun ambiguousOfferSwapsMonthAndDay() {
        val view = OcrPipeline.interpret("EXP 03/04/2027", today) as OcrView.Confirm
        assertEquals(LocalDate(2027, 3, 4), view.expiry)
        assertEquals(LocalDate(2027, 4, 3), view.alternate)
        assertTrue(view.yellow)
    }

    @Test
    fun letterOStillOffersTheOtherReading() {
        val view = OcrPipeline.interpret("EXP 03/04/2O27", today) as OcrView.Confirm
        assertEquals(LocalDate(2027, 3, 4), view.expiry)
        assertEquals(LocalDate(2027, 4, 3), view.alternate)
        assertTrue(view.yellow)
    }

    @Test
    fun occludedQuantityIsManual() {
        val text = java.nio.file.Path.of(System.getProperty("repo.root"), "testdata/ocr/09-unclear-occlusion.txt").toFile().readText()
        assertTrue(OcrPipeline.interpret(text, today) is OcrView.Manual)
    }
}
