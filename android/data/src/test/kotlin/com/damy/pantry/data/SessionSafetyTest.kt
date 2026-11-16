package com.damy.pantry.data

import com.pantrykeeper.core.model.FieldLimits
import java.io.IOException
import javax.crypto.KeyGenerator
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

@OptIn(ExperimentalCoroutinesApi::class)
class SessionSafetyTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun main() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun clear() {
        Dispatchers.resetMain()
    }

    @Test
    fun expiredSessionWipesCacheAndTheNextAccountSyncsFromZero() = runTest(dispatcher) {
        val api = ScriptApi()
        val cache = MemoryCache()
        val repo = repo(api, cache)
        cache.replace(listOf(item("a", "A家药", 900)))
        cache.setCursor(900)
        repo.restoreFromCache()
        repo.session.token = "token-a"
        api.syncHandler = { throw PantryCallException(401, "unauthorized", "登录已失效") }
        assertFailsWith<PantryCallException> { repo.pull() }
        assertTrue(repo.items.value.isEmpty())
        assertEquals(0, cache.cursor())
        assertNull(repo.session.token)
        assertNull(repo.session.loadFamily())

        api.loginResult = LoginResult("b", "乙", "token-b")
        api.meResult = Me("b", "乙", "09:00", "fb")
        api.syncHandler = { since ->
            api.since.add(since)
            SyncPage(rev = 4, items = listOf(item("m", "B家米", 1), item("o", "B家油", 2)))
        }
        repo.login("13" + "8" + "0".repeat(8), "k23456789")
        assertEquals(listOf(0L), api.since)
        assertEquals(setOf("B家米", "B家油"), repo.items.value.map { it.name }.toSet())
    }

    @Test
    fun removedMemberWipesCursorSoTheNextFamilyIsAFullSync() = runTest(dispatcher) {
        val api = ScriptApi()
        val cache = MemoryCache()
        val repo = repo(api, cache)
        cache.replace(listOf(item("a", "A家药", 900)))
        cache.setCursor(900)
        repo.restoreFromCache()
        repo.session.token = "token-a"
        api.syncHandler = { throw PantryCallException(403, "not_in_family", "不在家庭中") }
        assertFailsWith<PantryCallException> { repo.pull() }
        assertTrue(repo.items.value.isEmpty())
        assertEquals(0, cache.cursor())
        assertEquals("token-a", repo.session.token)

        api.syncHandler = { since ->
            api.since.add(since)
            SyncPage(rev = 2, items = listOf(item("n", "新家米", 1)))
        }
        repo.joinFamily("AB2345")
        assertEquals(listOf(0L), api.since)
        assertEquals(listOf("新家米"), repo.items.value.map { it.name })
    }

    @Test
    fun sessionRevokedWipesTheSameWayAsA401() = runTest(dispatcher) {
        val api = ScriptApi()
        val cache = MemoryCache()
        val repo = repo(api, cache)
        cache.replace(listOf(item("a", "A家药", 900)))
        cache.setCursor(900)
        repo.restoreFromCache()
        repo.session.token = "token-a"
        repo.handleSocket("""{"t":"session_revoked"}""")
        assertTrue(repo.items.value.isEmpty())
        assertEquals(0, cache.cursor())
        assertNull(repo.session.token)
    }

    @Test
    fun bootKeepsTheCacheWhenTheServerCannotBeReached() = runTest(dispatcher) {
        val api = ScriptApi()
        val cache = MemoryCache()
        val repo = repo(api, cache)
        cache.replace(listOf(item("a", "A家药", 3)))
        repo.session.token = "token-a"
        api.meError = IOException("failed to connect to pantry.example")
        repo.boot()
        assertEquals(listOf("A家药"), repo.items.value.map { it.name })
        assertFalse(repo.online.value)
        assertEquals("暂时连不上服务器", repo.notice.value)
        assertFalse(repo.notice.value!!.contains("example"))

        api.meError = SSLPeerUnverifiedException("pin mismatch pantry.example")
        repo.setOnline(true)
        repo.boot()
        assertEquals(listOf("A家药"), repo.items.value.map { it.name })
        assertEquals("暂时连不上服务器", repo.notice.value)
    }

    @Test
    fun undoBarDismissesWhenItemsWereSkippedOrTheCallFails() = runTest(dispatcher) {
        val api = ScriptApi()
        val repo = repo(api, MemoryCache())
        repo.session.token = "token"
        val vm = ListViewModel(repo)
        vm.undo.value = UndoUi("t", 2, "used_up", 5, 0)
        api.undoResult = UndoOutcome(1, 0, "1 件已被他人修改，未撤销")
        vm.undo()
        advanceUntilIdle()
        assertNull(vm.undo.value)
        assertEquals("1 件已被他人修改，未撤销", vm.toast.value)

        api.undoError = IOException("pantry.example")
        vm.undo.value = UndoUi("t2", 1, "used_up", 4, 0)
        vm.undo()
        advanceUntilIdle()
        assertNull(vm.undo.value)
        assertEquals("当前无网络，请稍后重试", vm.hint.value)
        assertFalse(vm.hint.value!!.contains("example"))
    }

    @Test
    fun unreachableServerRetriesAndComesBackOnline() = runTest(dispatcher) {
        val api = ScriptApi()
        val cache = MemoryCache()
        val repo = repo(api, cache)
        cache.replace(listOf(item("a", "A家药", 3)))
        repo.session.token = "token-a"
        api.meError = IOException("pantry.example")
        repo.boot()
        assertFalse(repo.online.value)
        assertEquals("暂时连不上服务器", repo.notice.value)
        api.meError = null
        repo.startForeground()
        runCurrent()
        assertTrue(repo.online.value)
        assertNull(repo.notice.value)
        assertEquals(listOf("A家药"), repo.items.value.map { it.name })
        repo.stopForeground()
    }

    @Test
    fun unreachableServerBacksOffBeforeTheNextProbe() = runTest(dispatcher) {
        val api = ScriptApi()
        val repo = repo(api, MemoryCache())
        repo.session.token = "token-a"
        api.meError = IOException("pantry.example")
        repo.boot()
        assertEquals(1, api.meCalls)
        repo.startForeground()
        runCurrent()
        assertEquals(2, api.meCalls)
        advanceTimeBy(9_999)
        assertEquals(2, api.meCalls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(3, api.meCalls)
        assertFalse(repo.online.value)
        repo.stopForeground()
    }

    @Test
    fun displayNameUpdateReplacesTheDefault() = runTest(dispatcher) {
        val api = ScriptApi()
        val repo = repo(api, MemoryCache())
        repo.session.token = "token"
        repo.session.displayName = "用户"
        val me = repo.updateDisplayName("小米")
        assertEquals("小米", me.displayName)
        assertEquals("小米", repo.session.displayName)
        assertFailsWith<PantryCallException> { repo.updateDisplayName(" ") }
    }

    @Test
    fun limitsCountCodePoints() {
        val emoji = "😀"
        assertEquals(emoji + emoji, FieldLimits.takeCodePoints(emoji.repeat(3), 2))
        val clipped = FieldLimits.takeCodePoints(emoji.repeat(3), 2)
        assertEquals(2, clipped.codePointCount(0, clipped.length))
        assertEquals(4, clipped.length)
    }

    @Test
    fun keystoreFailureFallsBackToAFreshLogin() {
        var attempts = 0
        var deleted = false
        val opened = openSession(
            load = {
                attempts += 1
                error("keystore down")
            },
            deleteAlias = { deleted = true },
        )
        assertEquals(2, attempts)
        assertTrue(deleted)
        assertTrue(opened.session is MemorySession)
        assertEquals("安全存储不可用，请重新登录", opened.message)
        var second = 0
        val recovered = openSession(
            load = {
                second += 1
                if (second == 1) error("once") else MemorySession()
            },
            deleteAlias = {},
        )
        assertEquals(2, second)
        assertNull(recovered.message)
    }

    @Test
    fun tokenCipherRoundTripHidesThePlainValue() {
        val crypto = SessionCrypto(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
        val stored = crypto.encrypt("session-token-value")
        assertFalse(stored.contains("session-token-value"))
        assertEquals("session-token-value", crypto.decrypt(stored))
        assertEquals("pantry-session", AndroidKeystoreSession.ALIAS)
    }

    private fun repo(api: PantryApi, cache: MemoryCache) = PantryRepository(
        api = api,
        cache = cache,
        session = MemorySession(),
        scope = CoroutineScope(dispatcher),
        today = { LocalDate(2026, 11, 15) },
        io = dispatcher,
    )
}

private fun item(id: String, name: String, rev: Long) =
    CachedItem(id = id, name = name, category = "food", quantity = 1, expiryDate = "2027-01-01", status = "in_stock", rev = rev)

private class ScriptApi : PantryApi {
    val since = mutableListOf<Long>()
    var syncHandler: suspend (Long) -> SyncPage = { SyncPage() }
    var meResult = Me("u", "用户", "09:00", "f")
    var meError: Exception? = null
    var meCalls = 0
    var loginResult = LoginResult("u", "用户", "token")
    var undoResult = UndoOutcome(0, 1, null)
    var undoError: Exception? = null

    override suspend fun requestCode(phone: String) = Unit
    override suspend fun login(phone: String, code: String) = loginResult
    override suspend fun logout() = Unit
    override suspend fun updateDisplayName(name: String) = meResult.copy(displayName = name)
    override suspend fun me(): Me {
        meCalls += 1
        meError?.let { throw it }
        return meResult
    }
    override suspend fun createFamily(name: String) = family()
    override suspend fun joinFamily(code: String) = family()
    override suspend fun leaveFamily() = Unit
    override suspend fun currentFamily() = family()
    override suspend fun renameFamily(name: String) = family()
    override suspend fun resetInvite() = family()
    override suspend fun removeMember(userId: String) = Unit
    override suspend fun transferAdmin(userId: String) = family()
    override suspend fun createItem(draft: ItemDraft, today: String) = error("unused")
    override suspend fun patchItem(id: String, baseRev: Long, fields: JsonObject) = error("unused")
    override suspend fun batch(ids: List<String>, action: String) = BatchOutcome("undo-1", 1)
    override suspend fun undo(token: String): UndoOutcome {
        undoError?.let { throw it }
        return undoResult
    }
    override suspend fun sync(since: Long): SyncPage = syncHandler(since)
    override suspend fun barcode(code: String) = BarcodeHit(found = false, reason = "not_found")
    override suspend fun uploadPhoto(itemId: String, bytes: ByteArray) = error("unused")
    override suspend fun photoThumb(photoId: String) = error("unused")
    override fun openSocket(onText: (String) -> Unit, onClosed: () -> Unit): () -> Unit = {}
    private fun family() = Family("f", "大米一家", "u", "AB2345", "2026-11-22T00:00:00Z", "admin")
}
