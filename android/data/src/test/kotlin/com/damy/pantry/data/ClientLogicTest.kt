package com.damy.pantry.data

import com.pantrykeeper.core.date.ShelfUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ClientLogicTest {
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
    fun syncMergeReplacesNewerRevAndDropsTombstones() {
        val local = listOf(item("a", "旧", 1), item("b", "留", 2))
        val merged = SyncMerger.merge(local, listOf(item("a", "新", 3), item("c", "来", 1)), setOf("b"))
        assertEquals(setOf("a", "c"), merged.map { it.id }.toSet())
        assertEquals("新", merged.first { it.id == "a" }.name)
        val kept = SyncMerger.merge(listOf(item("a", "新", 5)), listOf(item("a", "旧包", 4)), emptySet())
        assertEquals("新", kept.single().name)
    }

    @Test
    fun groupsFollowCoreRules() {
        val today = LocalDate(2026, 11, 15)
        val rows = listOf(
            item("e", "过期酱", 1, "2026-11-01"),
            item("t", "今天奶", 1, "2026-11-15"),
            item("w", "一周", 1, "2026-11-20"),
            item("m", "月内", 1, "2026-12-10"),
            item("l", "很远", 1, "2027-06-01"),
            item("h", "历史", 1, "2026-11-01", "used_up"),
        )
        val groups = groupItems(today, rows.filter { it.status == "in_stock" }, "")
        assertEquals(listOf("已过期", "7 天内（含今天）", "8–30 天", "更远"), groups.map { it.title })
        assertEquals(listOf("过期酱"), groups[0].items.map { it.name })
        assertEquals(listOf("今天奶", "一周"), groups[1].items.map { it.name })
        assertEquals("今天到期", daysLabel(0))
        assertEquals("已过期 14 天", daysLabel(-14))
        assertEquals("还有 106 天", daysLabel(106))
        assertEquals(listOf("今天奶"), groupItems(today, rows.filter { it.status == "in_stock" }, "奶").flatMap { it.items.map { item -> item.name } })
    }

    @Test
    fun shelfMonthClampsToMonthEnd() {
        val today = LocalDate(2026, 11, 15)
        assertEquals(LocalDate(2026, 2, 28), shelfExpiry(today, LocalDate(2026, 1, 31), 1, ShelfUnit.MONTH))
        assertEquals(LocalDate(2028, 2, 29), shelfExpiry(today, LocalDate(2028, 1, 31), 1, ShelfUnit.MONTH))
        assertTrue(shelfOrigin(today, null).contains("入库当天"))
    }

    @Test
    fun inviteAlphabetRejectsAmbiguousCharacters() {
        assertEquals("AB2345", normalizeInvite("ab2345"))
        assertNull(normalizeInvite("A0B234"))
        assertNull(normalizeInvite("AO2345"))
        assertNull(normalizeInvite("12345"))
    }

    @Test
    fun phoneMaskHidesTheMiddle() {
        val phone = "13" + "8" + "0".repeat(8)
        assertEquals("138****0000", maskPhone(phone))
        assertFalse(maskPhone(phone).contains(phone))
    }

    @Test
    fun undoBarHidesZeroSkipsAndKeepsCountdownSeparate() {
        val text = undoBarText("used_up", 2, 5, 0)
        assertEquals("已将 2 件标记为已用完，已移入历史", text)
        assertFalse(text.contains("跳过"))
        assertEquals("5 秒", undoCountdown(5))
        assertTrue(undoBarText("discarded", 1, 4, 1).contains("1 件已被他人修改，未撤销"))
    }

    @Test
    fun loginStaysDisabledUntilTheAgreementAndCode() = runTest {
        val repo = repo(FakeApi())
        val vm = LoginViewModel(repo)
        assertFalse(vm.canLogin())
        vm.phone.value = "13" + "8" + "0".repeat(8)
        vm.code.value = "123456"
        assertFalse(vm.canLogin())
        vm.agreed.value = true
        assertTrue(vm.canLogin())
        vm.code.value = "12345"
        assertFalse(vm.canLogin())
        vm.code.value = "k23456"
        assertFalse(vm.canLogin())
        vm.code.value = "123456"
        vm.agreed.value = false
        vm.requestCode()
        advanceTimeBy(1)
        assertEquals("请先勾选用户协议和隐私政策", vm.error.value)
    }

    @Test
    fun offlineManageDoesNotSelect() = runTest {
        val repo = repo(FakeApi())
        repo.setOnline(false)
        val vm = ListViewModel(repo)
        vm.toggleManage()
        assertEquals("恢复网络后再修改", vm.hint.value)
        assertFalse(vm.selecting.value)
    }

    @Test
    fun repositoryPullMergesAndResets() = runTest {
        val api = FakeApi()
        val repo = repo(api)
        repo.session.token = "token"
        api.page = SyncPage(
            reset = false,
            rev = 3,
            items = listOf(item("a", "牛奶", 2), item("b", "酱油", 3)),
            tombstones = listOf(Tombstone("gone", 3)),
        )
        repo.pull()
        assertEquals(setOf("牛奶", "酱油"), repo.items.value.map { it.name }.toSet())
        api.page = SyncPage(reset = true, rev = 1, items = listOf(item("z", "只剩", 1)))
        repo.pull()
        assertEquals(listOf("只剩"), repo.items.value.map { it.name })
        assertEquals(1, api.fullSyncs)
    }

    private fun repo(api: PantryApi) = PantryRepository(
        api = api,
        cache = MemoryCache(),
        session = MemorySession(),
        scope = CoroutineScope(dispatcher),
        today = { LocalDate(2026, 11, 15) },
    )
}

private fun item(id: String, name: String, rev: Long, expiry: String = "2027-01-01", status: String = "in_stock") =
    CachedItem(id = id, name = name, category = "food", quantity = 1, expiryDate = expiry, status = status, rev = rev)

private class FakeApi : PantryApi {
    var page = SyncPage()
    var fullSyncs = 0
    override suspend fun requestCode(phone: String) = Unit
    override suspend fun login(phone: String, code: String) = LoginResult("u", "用户", "token")
    override suspend fun logout() = Unit
    override suspend fun me() = Me("u", "用户", "09:00", "f")
    override suspend fun updateDisplayName(name: String) = Me("u", name, "09:00", "f")
    override suspend fun createFamily(name: String) = family()
    override suspend fun joinFamily(code: String) = family()
    override suspend fun leaveFamily() = Unit
    override suspend fun currentFamily() = family()
    override suspend fun renameFamily(name: String) = family().copy(name = name)
    override suspend fun resetInvite() = family()
    override suspend fun removeMember(userId: String) = Unit
    override suspend fun transferAdmin(userId: String) = family()
    override suspend fun createItem(draft: ItemDraft, today: String) = item("n", draft.name, 1)
    override suspend fun patchItem(id: String, baseRev: Long, fields: JsonObject) =
        PatchBody(item(id, "改", baseRev + 1), "名称已被乙修改，已用你的修改覆盖")

    override suspend fun batch(ids: List<String>, action: String) = BatchOutcome("undo-1", 4)
    override suspend fun undo(token: String) = UndoOutcome(0, 1, null)
    override suspend fun sync(since: Long): SyncPage {
        if (since == 0L && page.reset) fullSyncs += 1
        return if (page.reset && since != 0L) page else if (since == 0L && page.reset) {
            SyncPage(reset = false, rev = page.rev, items = page.items)
        } else page
    }

    override suspend fun barcode(code: String) = BarcodeHit(found = false, reason = "not_found")
    override suspend fun uploadPhoto(itemId: String, bytes: ByteArray) = item(itemId, "照片", 2)
    override suspend fun photoThumb(photoId: String) = byteArrayOf()
    override fun openSocket(onText: (String) -> Unit, onClosed: () -> Unit): () -> Unit = {}
    private fun family() = Family("f", "大米一家", "u", "AB2345", "2026-11-22T00:00:00Z", "admin")
}
