package com.damy.pantry.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.damy.pantry.data.CachedItem
import com.damy.pantry.data.Family
import com.damy.pantry.data.HistoryViewModel
import com.damy.pantry.data.ListViewModel
import com.damy.pantry.data.Member
import com.damy.pantry.data.MemoryCache
import com.damy.pantry.data.MemorySession
import com.damy.pantry.data.PantryRepository
import com.damy.pantry.data.PendingPhoto
import com.damy.pantry.reminder.ReminderAccessScreen
import com.damy.pantry.reminder.ReminderNav
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = android.app.Application::class)
class ReminderShots {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val today = LocalDate(2026, 11, 15)

    @Before
    fun prepare() {
        ReminderNav.open = false
        PendingPhoto.clear()
    }

    @After
    fun cleanup() {
        ReminderNav.open = false
        PendingPhoto.clear()
    }

    @Test
    fun sentMarkerClearsForTheNextAccount() {
        val context = compose.activity
        context.getSharedPreferences("pantry-reminder", android.content.Context.MODE_PRIVATE)
            .edit().putString("sent", java.time.LocalDate.now().toString()).apply()
        check(com.damy.pantry.reminder.ReminderRuntime.sentToday(context))
        com.damy.pantry.reminder.ReminderRuntime.clearSent(context)
        check(!com.damy.pantry.reminder.ReminderRuntime.sentToday(context))
    }

    @Test
    fun reminderList() {
        val home = home(items())
        show { HomeScreen(home.repo, home.list, home.history, today, {}, {}, {}, {}, {}, {}, {}) }
        compose.onNodeWithTag("tab_remind").performClick()
        capture("04-remind-list")
    }

    @Test
    fun notifyTime() {
        val home = home(emptyList())
        home.repo.session.notifyTime = "08:30"
        show {
            val scope = rememberCoroutineScope()
            com.damy.pantry.reminder.NotifyTimeScreen(home.repo, scope) {}
        }
        capture("10-notify-time")
    }

    @Test
    fun reminderCardOpensTheItem() {
        var opened: String? = null
        val home = home(items())
        show { HomeScreen(home.repo, home.list, home.history, today, {}, { opened = it }, {}, {}, {}, {}, {}) }
        compose.onNodeWithTag("tab_remind").performClick()
        compose.onNodeWithTag("item_牛奶").performClick()
        check(opened == "milk")
    }

    @Test
    fun notificationExplain() {
        val home = home(emptyList())
        show {
            val scope = rememberCoroutineScope()
            com.damy.pantry.reminder.NotifyTimeScreen(home.repo, scope) {}
        }
        org.robolectric.Shadows.shadowOf(compose.activity).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        compose.onNodeWithTag("time_save").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("notif_explain").fetchSemanticsNodes().isNotEmpty()
        }
        capture("11-notif-explain")
    }

    @Test
    fun reminderAccess() {
        show { ReminderAccessScreen {} }
        capture("17-reminder-access")
    }

    @Test
    fun retryBannerAppearsAndLeavesWithTheFlow() {
        val home = home(items())
        show { HomeScreen(home.repo, home.list, home.history, today, {}, {}, {}, {}, {}, {}, {}) }
        check(compose.onAllNodesWithTag("retry_banner").fetchSemanticsNodes().isEmpty())
        PendingPhoto.holdForItem("milk", byteArrayOf(1, 2, 3))
        compose.waitForIdle()
        check(compose.onAllNodesWithTag("retry_banner").fetchSemanticsNodes().isNotEmpty())
        capture("retry-banner")
        PendingPhoto.remove("milk")
        PendingPhoto.showLost("milk")
        compose.waitForIdle()
        capture("m13-photo-lost")
        compose.waitForIdle()
        check(compose.onAllNodesWithTag("retry_banner").fetchSemanticsNodes().isEmpty())
    }

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { PantryTheme { content() } }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val root = File(System.getProperty("repo.root") ?: ".")
        val file = File(root, "docs/screenshots/reminder/$name.png")
        file.parentFile?.mkdirs()
        if (file.exists()) file.delete()
        captureScreenRoboImage(file)
        if (!file.exists() || file.length() < 100) {
            val node = if (compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()) {
                compose.onNode(isDialog())
            } else {
                compose.onRoot()
            }
            val bitmap = node.captureToImage().asAndroidBitmap()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        check(file.exists() && file.length() > 100) { "screenshot $name was not written" }
        val artifact = File("/opt/cursor/artifacts", file.name)
        artifact.parentFile?.mkdirs()
        file.copyTo(artifact, overwrite = true)
    }

    private fun home(rows: List<CachedItem>): Home {
        val session = MemorySession().apply {
            token = "token"
            userId = "me"
            displayName = "大米"
            notifyTime = "09:00"
        }
        val repo = PantryRepository(QuietApi, MemoryCache(), session, CoroutineScope(Dispatchers.Unconfined))
        repo.items.value = rows
        repo.family.value = Family(
            "f",
            "大米一家",
            "me",
            "AB2345",
            "2026-11-22T00:00:00Z",
            "admin",
            listOf(Member("me", "大米", "admin", "2026-03-01T00:00:00Z")),
        )
        return Home(repo, ListViewModel(repo), HistoryViewModel(repo))
    }

    private fun items() = listOf(
        item("milk", "牛奶", "2026-11-08"),
        item("soy", "酱油", "2026-11-18", location = "橱柜"),
        item("rice", "大米", "2026-11-22"),
        item("salt", "盐", "2026-12-10", location = "橱柜"),
        item("vinegar", "醋", "2027-03-01", location = "橱柜"),
    )

    private fun item(id: String, name: String, expiry: String, location: String = "冰箱") =
        CachedItem(id = id, name = name, category = "food", quantity = 1, location = location, expiryDate = expiry, reminderTiers = listOf("D30", "D5"), status = "in_stock", rev = 1)

    private data class Home(val repo: PantryRepository, val list: ListViewModel, val history: HistoryViewModel)
}
