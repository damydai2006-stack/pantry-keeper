package com.damy.pantry.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.damy.pantry.data.Family
import com.damy.pantry.data.ItemDraft
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
class FamilyFailureTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun createHidesTheHostName() {
        val api = FamilyApi()
        val repo = repo(api)
        api.createError = IOException("pantry.example")
        compose.setContent { PantryTheme { FamilyCreateScreen(repo, {}, {}) } }
        compose.onNodeWithTag("family_name").performTextInput("大米一家")
        compose.onNodeWithTag("family_save").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("当前无网络，请稍后重试").assertExists()
        compose.onNodeWithText("pantry.example", substring = true).assertDoesNotExist()
    }

    @Test
    fun joinHidesTheHostName() {
        val api = FamilyApi()
        val repo = repo(api)
        api.joinError = IOException("pantry.example")
        compose.setContent { PantryTheme { FamilyJoinScreen(repo, {}, {}) } }
        compose.onNodeWithTag("join_code").performTextInput("AB2345")
        compose.onNodeWithTag("join_submit").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("当前无网络，请稍后重试").assertExists()
        compose.onNodeWithText("pantry.example", substring = true).assertDoesNotExist()
    }

    private fun repo(api: FamilyApi) = PantryRepository(
        api,
        MemoryCache(),
        MemorySession().apply { token = "token" },
        CoroutineScope(Dispatchers.Unconfined),
        io = Dispatchers.Unconfined,
    )
}

private class FamilyApi : PantryApi {
    var createError: Exception? = null
    var joinError: Exception? = null
    override suspend fun requestCode(phone: String) = Unit
    override suspend fun login(phone: String, code: String) = error("unused")
    override suspend fun logout() = Unit
    override suspend fun me() = error("unused")
    override suspend fun updateDisplayName(name: String) = error("unused")
    override suspend fun createFamily(name: String): Family {
        createError?.let { throw it }
        error("unused")
    }
    override suspend fun joinFamily(code: String): Family {
        joinError?.let { throw it }
        error("unused")
    }
    override suspend fun leaveFamily() = Unit
    override suspend fun currentFamily() = error("unused")
    override suspend fun renameFamily(name: String) = error("unused")
    override suspend fun resetInvite() = error("unused")
    override suspend fun removeMember(userId: String) = Unit
    override suspend fun transferAdmin(userId: String) = error("unused")
    override suspend fun createItem(draft: ItemDraft, today: String) = error("unused")
    override suspend fun patchItem(id: String, baseRev: Long, fields: JsonObject) = error("unused")
    override suspend fun batch(ids: List<String>, action: String) = error("unused")
    override suspend fun undo(token: String) = error("unused")
    override suspend fun sync(since: Long) = error("unused")
    override suspend fun barcode(code: String) = error("unused")
    override fun openSocket(onText: (String) -> Unit, onClosed: () -> Unit): () -> Unit = {}
}
