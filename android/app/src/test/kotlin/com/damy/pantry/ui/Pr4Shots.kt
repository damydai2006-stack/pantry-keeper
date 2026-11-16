package com.damy.pantry.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.damy.pantry.data.CachedItem
import com.damy.pantry.data.Family
import com.damy.pantry.data.HistoryViewModel
import com.damy.pantry.data.ListViewModel
import com.damy.pantry.data.LoginViewModel
import com.damy.pantry.data.Member
import com.damy.pantry.data.MemoryCache
import com.damy.pantry.data.MemorySession
import com.damy.pantry.data.PantryApi
import com.damy.pantry.data.PantryRepository
import com.damy.pantry.data.UndoUi
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = android.app.Application::class)
class Pr4Shots {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val today = LocalDate(2026, 11, 15)
    private val phone = "13" + "8" + "0".repeat(8)

    @Test
    fun listSelectAndDelete() {
        val stock = home(items())
        show(content = { HomeScreen(stock.repo, stock.list, stock.history, today, {}, {}, {}, {}, {}, {}, {}) })
        capture("01-list")
        stock.list.selecting.value = true
        stock.list.selected.value = setOf("milk", "soy")
        compose.waitForIdle()
        capture("05-list-select")
        compose.onNodeWithTag("batch_delete").performClick()
        capture("16-dlg-delete")
    }

    @Test
    fun emptyList() {
        val empty = home(listOf())
        show(content = { HomeScreen(empty.repo, empty.list, empty.history, today, {}, {}, {}, {}, {}, {}, {}) })
        capture("13-list-empty")
    }

    @Test
    fun undoBar() {
        val undo = home(items())
        undo.list.undo.value = UndoUi("undo-token", 2, "used_up", 5, 0)
        show(content = { HomeScreen(undo.repo, undo.list, undo.history, today, {}, {}, {}, {}, {}, {}, {}) })
        capture("15-list-undo")
    }

    @Test
    fun offlineList() {
        val offline = home(items())
        offline.repo.setOnline(false)
        show(content = { HomeScreen(offline.repo, offline.list, offline.history, today, {}, {}, {}, {}, {}, {}, {}) })
        capture("18-list-offline")
    }

    @Test
    fun history() {
        val history = home(items() + item("old", "大米", "2026-10-01", "used_up"))
        show(content = { HomeScreen(history.repo, history.list, history.history, today, {}, {}, {}, {}, {}, {}, {}) })
        compose.onNodeWithTag("tab_history").performClick()
        capture("12-history")
    }

    @Test
    fun login() {
        val loginRepo = home(listOf())
        val loginVm = LoginViewModel(loginRepo.repo)
        loginVm.phone.value = phone
        loginVm.agreed.value = true
        loginVm.secondsLeft.value = 59
        show(content = { LoginScreen(loginVm, {}, {}) })
        capture("07-login")
    }

    @Test
    fun membersAndMakeAdmin() {
        val members = home(listOf(), family(others = true))
        show(content = { MembersScreen(members.repo, {}, {}) })
        capture("06-members")
        compose.onNodeWithTag("make_admin_小米").performScrollTo().performClick()
        capture("28-dlg-make-admin")
    }

    @Test
    fun adminLeaveTransfer() {
        val transfer = home(listOf(), family(others = true))
        show(content = { MembersScreen(transfer.repo, {}, {}) })
        compose.onNodeWithTag("leave_family").performScrollTo().performClick()
        capture("24-dlg-admin-leave-transfer")
    }

    @Test
    fun adminLeaveLast() {
        val last = home(listOf(), family(others = false))
        show(content = { MembersScreen(last.repo, {}, {}) })
        compose.onNodeWithTag("leave_family").performScrollTo().performClick()
        capture("25-dlg-admin-leave-last")
    }

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { PantryTheme { content() } }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val file = File(System.getProperty("pantry.shots"), "$name.png")
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

    private fun home(rows: List<CachedItem>, family: Family = family(others = false)): Home {
        val session = MemorySession().apply {
            token = "token"
            userId = "me"
            displayName = "大米"
            phoneMask = phone.take(3) + "****" + phone.takeLast(4)
        }
        val repo = PantryRepository(QuietApi, MemoryCache(), session, CoroutineScope(Dispatchers.Unconfined))
        repo.items.value = rows
        repo.family.value = family
        return Home(repo, ListViewModel(repo), HistoryViewModel(repo))
    }

    private fun family(others: Boolean): Family {
        val members = buildList {
            add(Member("me", "大米", "admin", "2026-03-01T00:00:00Z"))
            if (others) {
                add(Member("m2", "小米", "member", "2026-06-12T00:00:00Z"))
                add(Member("m3", "糯米", "member", "2026-09-02T00:00:00Z"))
            }
        }
        return Family("f", "大米一家", "me", "AB2345", "2026-11-22T00:00:00Z", "admin", members)
    }

    private fun items() = listOf(
        item("milk", "牛奶", "2026-11-08", photoId = "p1"),
        item("soy", "酱油", "2026-11-18", location = "橱柜"),
        item("rice", "大米", "2026-11-22"),
        item("salt", "盐", "2026-12-10", location = "橱柜"),
        item("vinegar", "醋", "2027-03-01", location = "橱柜"),
    )

    private fun item(id: String, name: String, expiry: String, status: String = "in_stock", location: String = "冰箱", photoId: String? = null) =
        CachedItem(id = id, name = name, category = "food", quantity = 1, location = location, expiryDate = expiry, status = status, rev = 1, photoId = photoId)

    private data class Home(val repo: PantryRepository, val list: ListViewModel, val history: HistoryViewModel)
}

internal object QuietApi : PantryApi {
    override suspend fun requestCode(phone: String) = Unit
    override suspend fun login(phone: String, code: String) = error("unused")
    override suspend fun logout() = Unit
    override suspend fun me() = error("unused")
    override suspend fun updateDisplayName(name: String) = error("unused")
    override suspend fun createFamily(name: String) = error("unused")
    override suspend fun joinFamily(code: String) = error("unused")
    override suspend fun leaveFamily() = Unit
    override suspend fun currentFamily() = error("unused")
    override suspend fun renameFamily(name: String) = error("unused")
    override suspend fun resetInvite() = error("unused")
    override suspend fun removeMember(userId: String) = Unit
    override suspend fun transferAdmin(userId: String) = error("unused")
    override suspend fun createItem(draft: com.damy.pantry.data.ItemDraft, today: String) = error("unused")
    override suspend fun patchItem(id: String, baseRev: Long, fields: JsonObject) = error("unused")
    override suspend fun batch(ids: List<String>, action: String) = error("unused")
    override suspend fun undo(token: String) = error("unused")
    override suspend fun sync(since: Long) = error("unused")
    override suspend fun barcode(code: String) = error("unused")
    override suspend fun uploadPhoto(itemId: String, bytes: ByteArray) = error("unused")
    override suspend fun photoThumb(photoId: String) = error("unused")
    override fun openSocket(onText: (String) -> Unit, onClosed: () -> Unit): () -> Unit = {}
}
