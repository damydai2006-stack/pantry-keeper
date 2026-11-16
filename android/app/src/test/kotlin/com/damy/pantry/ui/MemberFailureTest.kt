package com.damy.pantry.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.damy.pantry.data.Family
import com.damy.pantry.data.ItemDraft
import com.damy.pantry.data.Member
import com.damy.pantry.data.MemoryCache
import com.damy.pantry.data.MemorySession
import com.damy.pantry.data.PantryApi
import com.damy.pantry.data.PantryRepository
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MemberFailureTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun removeAndLeaveFailuresStayOnScreen() {
        val api = MemberApi()
        val session = MemorySession().apply {
            token = "token"
            userId = "me"
            displayName = "大米"
        }
        val repo = PantryRepository(
            api,
            MemoryCache(),
            session,
            CoroutineScope(Dispatchers.Unconfined),
            io = Dispatchers.Unconfined,
        )
        repo.family.value = family()
        api.removeError = IOException("pantry.example")
        compose.setContent { PantryTheme { MembersScreen(repo, {}, {}) } }
        compose.onNodeWithTag("remove_member").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("confirm_remove").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("当前无网络，请稍后重试").assertExists()
        compose.onNodeWithText("pantry.example", substring = true).assertDoesNotExist()

        api.removeError = null
        api.leaveError = IOException("pantry.example")
        compose.onNodeWithTag("leave_family").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("confirm_transfer_leave").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("管理员已转让，但退出失败。当前无网络，请稍后重试").assertExists()
    }

    private fun family() = Family(
        "f",
        "大米一家",
        "me",
        "AB2345",
        "2026-11-22T00:00:00Z",
        "admin",
        listOf(
            Member("me", "大米", "admin", "2026-03-01T00:00:00Z"),
            Member("m2", "小米", "member", "2026-06-12T00:00:00Z"),
        ),
    )
}

private class MemberApi : PantryApi {
    var removeError: Exception? = null
    var leaveError: Exception? = null
    override suspend fun requestCode(phone: String) = Unit
    override suspend fun login(phone: String, code: String) = error("unused")
    override suspend fun logout() = Unit
    override suspend fun me() = error("unused")
    override suspend fun updateDisplayName(name: String) = error("unused")
    override suspend fun createFamily(name: String) = error("unused")
    override suspend fun joinFamily(code: String) = error("unused")
    override suspend fun leaveFamily() {
        leaveError?.let { throw it }
    }
    override suspend fun currentFamily() = error("unused")
    override suspend fun renameFamily(name: String) = error("unused")
    override suspend fun resetInvite() = error("unused")
    override suspend fun removeMember(userId: String) {
        removeError?.let { throw it }
    }
    override suspend fun transferAdmin(userId: String) = Family("f", "大米一家", userId, "AB2345", "2026-11-22T00:00:00Z", "member")
    override suspend fun createItem(draft: ItemDraft, today: String) = error("unused")
    override suspend fun patchItem(id: String, baseRev: Long, fields: JsonObject) = error("unused")
    override suspend fun batch(ids: List<String>, action: String) = error("unused")
    override suspend fun undo(token: String) = error("unused")
    override suspend fun sync(since: Long) = error("unused")
    override fun openSocket(onText: (String) -> Unit, onClosed: () -> Unit): () -> Unit = {}
}
