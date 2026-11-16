package com.damy.pantry.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.damy.pantry.capture.CameraDenied
import com.damy.pantry.capture.CameraExplain
import com.damy.pantry.capture.ConfirmScreen
import com.damy.pantry.capture.FailScreen
import com.damy.pantry.capture.OcrView
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.io.File
import kotlinx.datetime.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = android.app.Application::class)
class CaptureShots {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun compressStaysWithinTheLimit() {
        val bitmap = android.graphics.Bitmap.createBitmap(1800, 1200, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        val raw = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, raw)
        val out = com.damy.pantry.capture.compressForUpload(raw.toByteArray(), maxBytes = 80_000)
        org.junit.Assert.assertTrue(out.size <= 80_000)
        org.junit.Assert.assertEquals(0xFF.toByte(), out[0])
    }

    @Test
    fun confirm() {
        show {
            ConfirmScreen(
                OcrView.Confirm(LocalDate(2027, 3, 15), LocalDate(2026, 3, 15), null, false, emptyList(), null, "ocr_expiry"),
                photo = samplePhoto(),
                onBack = {},
                onManual = {},
                onNext = {},
            )
        }
        capture("02-confirm")
    }

    @Test
    fun ambiguous() {
        show {
            ConfirmScreen(
                OcrView.Confirm(
                    LocalDate(2027, 3, 4),
                    null,
                    null,
                    true,
                    listOf("日期有两种读法，请确认。"),
                    LocalDate(2027, 4, 3),
                    "ocr_expiry",
                ),
                photo = samplePhoto(),
                onBack = {},
                onManual = {},
                onNext = {},
            )
        }
        capture("19-confirm-ambig")
    }

    @Test
    fun shelfPicker() {
        show { com.damy.pantry.capture.ShelfPickScreen(LocalDate(2026, 11, 15), onBack = {}, onDone = {}) }
        capture("03-manual")
    }

    @Test
    fun recognizeFail() {
        show { FailScreen("批号 20250312", onRetake = {}, onManual = {}, onDate = {}) }
        capture("14-recognize-fail")
    }

    @Test
    fun cameraPermission() {
        show { CameraExplain(onCancel = {}, onAllow = {}) }
        capture("26-dlg-camera-pre")
    }

    @Test
    fun cameraDenied() {
        show { CameraDenied(onSettings = {}, onManual = {}) }
        capture("27-camera-denied")
    }

    private fun samplePhoto(): androidx.compose.ui.graphics.ImageBitmap {
        val bitmap = Bitmap.createBitmap(80, 48, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.parseColor("#1E4A62"))
        return bitmap.asImageBitmap()
    }

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { PantryTheme { content() } }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val dir = File(System.getProperty("pantry.shots")).parentFile.resolve("capture")
        val file = File(dir, "$name.png")
        file.parentFile?.mkdirs()
        if (file.exists()) file.delete()
        captureScreenRoboImage(file)
        if (!file.exists() || file.length() < 100) {
            val node = if (compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()) compose.onNode(isDialog()) else compose.onRoot()
            val bitmap = node.captureToImage().asAndroidBitmap()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        check(file.exists() && file.length() > 100) { "screenshot $name was not written" }
        file.copyTo(File("/opt/cursor/artifacts", file.name), overwrite = true)
    }
}
