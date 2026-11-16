package com.damy.pantry.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import com.damy.pantry.capture.ConfirmScreen
import com.damy.pantry.capture.FailScreen
import com.damy.pantry.capture.OcrView
import com.damy.pantry.capture.ShelfPickScreen
import com.damy.pantry.data.PendingPhoto
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h2000dp-xhdpi", application = android.app.Application::class)
class CaptureOwnershipTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @After
    fun clear() {
        PendingPhoto.clear()
    }

    @Test
    fun confirmSystemBackDropsThatCapture() {
        PendingPhoto.storeCapture("cap-confirm", byteArrayOf(1, 2, 3))
        PendingPhoto.holdForItem("item-a", byteArrayOf(9))
        var left = false
        compose.setContent {
            PantryTheme {
                ConfirmScreen(
                    OcrView.Confirm(LocalDate(2027, 3, 15), null, null, false, emptyList(), null, "ocr_expiry"),
                    photo = null,
                    onBack = { left = true },
                    onManual = {},
                    onNext = {},
                    captureId = "cap-confirm",
                )
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertTrue(left)
        assertNull(PendingPhoto.bytesFor("cap-confirm"))
        assertTrue(PendingPhoto.bytesFor("item-a")!!.contentEquals(byteArrayOf(9)))
    }

    @Test
    fun failSystemBackDropsThatCapture() {
        PendingPhoto.storeCapture("cap-fail", byteArrayOf(5))
        PendingPhoto.holdForItem("item-a", byteArrayOf(9))
        var left = false
        compose.setContent {
            PantryTheme {
                FailScreen("看不清", onRetake = {}, onManual = {}, onDate = {}, captureId = "cap-fail", onBack = { left = true })
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertTrue(left)
        assertNull(PendingPhoto.bytesFor("cap-fail"))
        assertTrue(PendingPhoto.bytesFor("item-a")!!.contentEquals(byteArrayOf(9)))
    }

    @Test
    fun shelfSystemBackDropsThatCapture() {
        PendingPhoto.storeCapture("cap-shelf", byteArrayOf(6))
        var left = false
        compose.setContent {
            PantryTheme {
                ShelfPickScreen(LocalDate(2026, 11, 15), onBack = { left = true }, onDone = {}, captureId = "cap-shelf")
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertTrue(left)
        assertNull(PendingPhoto.bytesFor("cap-shelf"))
    }

    @Test
    fun shelfBackButtonDropsThatCapture() {
        PendingPhoto.storeCapture("cap-shelf-btn", byteArrayOf(6))
        var left = false
        compose.setContent {
            PantryTheme {
                ShelfPickScreen(LocalDate(2026, 11, 15), onBack = { left = true }, onDone = {}, captureId = "cap-shelf-btn")
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("shelf_back").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(left)
        assertNull(PendingPhoto.bytesFor("cap-shelf-btn"))
    }
}
