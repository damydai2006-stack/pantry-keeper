package com.damy.pantry.data

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject

class PendingPhotoTest {
    @AfterTest
    fun reset() {
        PendingPhoto.clear()
        PendingPhoto.directory = null
        PendingPhoto.readFile = { it.readBytes() }
    }

    @Test
    fun failedUploadSurvivesANewProcessAndLogoutDeletesIt() = runBlocking {
        val dir = File.createTempFile("pending", "dir")
        dir.delete()
        dir.mkdirs()
        PendingPhoto.bind(dir)
        PendingPhoto.holdForItem("item-a", byteArrayOf(4, 4))
        assertTrue(File(dir, "item-a").isFile)
        assertEquals(setOf("item-a"), PendingPhoto.items.value.keys)

        PendingPhoto.bind(dir)
        assertTrue(PendingPhoto.bytesFor("item-a")!!.contentEquals(byteArrayOf(4, 4)))
        assertEquals(setOf("item-a"), PendingPhoto.items.value.keys)

        val repo = repo(TinyApi())
        repo.session.token = "token"
        repo.logout()
        assertNull(PendingPhoto.bytesFor("item-a"))
        assertTrue(PendingPhoto.items.value.isEmpty())
        assertFalse(File(dir, "item-a").exists())
    }

    @Test
    fun unreadableFileTellsTheUserThePhotoIsLost() {
        val dir = File.createTempFile("pending-lost", "dir")
        dir.delete()
        dir.mkdirs()
        File(dir, "item-a").writeBytes(byteArrayOf(1, 2, 3))
        PendingPhoto.readFile = { error("cannot read") }
        PendingPhoto.bind(dir)
        assertTrue("item-a" in PendingPhoto.lostItems.value)
        assertEquals(
            "照片未上传，而且已经不在这台手机里。请到物品编辑页重新拍摄或添加。",
            PendingPhoto.LOST_BODY,
        )
        assertTrue(PendingPhoto.items.value.isEmpty())
        assertFalse(File(dir, "item-a").exists())
        PendingPhoto.dismissLost("item-a")
        assertTrue(PendingPhoto.lostItems.value.isEmpty())
    }

    @Test
    fun retryFileIsRenamedIntoPlaceAndTmpFilesAreIgnored() {
        val dir = File.createTempFile("pending-atomic", "dir")
        dir.delete()
        dir.mkdirs()
        PendingPhoto.bind(dir)
        PendingPhoto.holdForItem("item-a", byteArrayOf(4, 4))
        assertTrue(File(dir, "item-a").isFile)
        assertFalse(File(dir, "item-a.tmp").exists())
        File(dir, "item-b.tmp").writeBytes(byteArrayOf(9))
        PendingPhoto.bind(dir)
        assertNull(PendingPhoto.bytesFor("item-b"))
        assertTrue(PendingPhoto.bytesFor("item-a")!!.contentEquals(byteArrayOf(4, 4)))
    }

    @Test
    fun anotherCaptureDoesNotDropTheSavedRetryFile() {
        val dir = File.createTempFile("pending-keep", "dir")
        dir.delete()
        dir.mkdirs()
        PendingPhoto.bind(dir)
        PendingPhoto.holdForItem("item-a", byteArrayOf(9))
        val next = PendingPhoto.beginCapture()
        assertNull(PendingPhoto.bytesFor(next))
        assertTrue(File(dir, "item-a").isFile)
        assertTrue(PendingPhoto.bytesFor("item-a")!!.contentEquals(byteArrayOf(9)))
    }

    @Test
    fun deleteAndTombstoneAndLeavingTheFamilyRemoveTheFile() = runBlocking {
        val dir = File.createTempFile("pending-drop", "dir")
        dir.delete()
        dir.mkdirs()
        PendingPhoto.bind(dir)
        PendingPhoto.holdForItem("item-a", byteArrayOf(1))
        PendingPhoto.holdForItem("item-b", byteArrayOf(2))
        val api = TinyApi()
        val cache = MemoryCache()
        cache.setCursor(4)
        val repo = repo(api, cache)
        repo.session.token = "token"
        api.page = SyncPage(rev = 5, tombstones = listOf(Tombstone("item-b", 5)), items = listOf(stored("item-a", 5)))
        repo.pull()
        assertNull(PendingPhoto.bytesFor("item-b"))
        assertFalse(File(dir, "item-b").exists())
        assertTrue(PendingPhoto.bytesFor("item-a")!!.contentEquals(byteArrayOf(1)))

        repo.batch(listOf("item-a"), "delete")
        assertNull(PendingPhoto.bytesFor("item-a"))
        assertFalse(File(dir, "item-a").exists())
        assertTrue(PendingPhoto.items.value.isEmpty())

        PendingPhoto.holdForItem("item-c", byteArrayOf(3))
        api.syncError = PantryCallException(403, "not_in_family", "不在家庭中")
        assertFailsWith<PantryCallException> { repo.pull() }
        assertNull(PendingPhoto.bytesFor("item-c"))
        assertFalse(File(dir, "item-c").exists())
    }

    @Test
    fun offlineNotifyTimeIsNotStoredAndCannotBeReplacedBySync() = runBlocking {
        val api = TinyApi()
        val repo = repo(api)
        repo.session.token = "token"
        repo.session.notifyTime = "09:00"
        repo.setOnline(false)
        assertFailsWith<OfflineException> { repo.updateNotifyTime("18:30") }
        assertEquals("09:00", repo.session.notifyTime)
        repo.setOnline(true)
        api.notifyError = IllegalStateException("down")
        assertFailsWith<IllegalStateException> { repo.updateNotifyTime("18:30") }
        assertEquals("09:00", repo.session.notifyTime)
        api.notifyError = null
        api.page = SyncPage(rev = 2, notifyTime = "09:00")
        repo.pull()
        assertEquals("09:00", repo.session.notifyTime)
        repo.updateNotifyTime("18:30")
        assertEquals("18:30", repo.session.notifyTime)
        assertEquals("18:30", api.savedNotify)
    }

    @Test
    fun flowUpdatesWhenARetryIsAddedAndRemoved() {
        PendingPhoto.holdForItem("item-a", byteArrayOf(7))
        assertTrue(PendingPhoto.items.value.getValue("item-a").contentEquals(byteArrayOf(7)))
        PendingPhoto.remove("item-a")
        assertTrue(PendingPhoto.items.value.isEmpty())
    }

    private fun repo(api: TinyApi, cache: MemoryCache = MemoryCache()) = PantryRepository(
        api = api,
        cache = cache,
        session = MemorySession(),
        scope = CoroutineScope(Dispatchers.Unconfined),
        today = { LocalDate(2026, 11, 15) },
        io = Dispatchers.Unconfined,
    )

    private fun stored(id: String, rev: Long) =
        CachedItem(id = id, name = id, category = "food", quantity = 1, expiryDate = "2027-01-01", status = "in_stock", rev = rev)
}

private class TinyApi : PantryApi {
    var page = SyncPage()
    var syncError: Exception? = null
    var notifyError: Exception? = null
    var savedNotify: String? = null
    override suspend fun requestCode(phone: String) = Unit
    override suspend fun login(phone: String, code: String) = LoginResult("u", "用户", "token")
    override suspend fun logout() = Unit
    override suspend fun me() = Me("u", "用户", "09:00", "f")
    override suspend fun updateDisplayName(name: String) = me()
    override suspend fun createFamily(name: String) = error("unused")
    override suspend fun joinFamily(code: String) = error("unused")
    override suspend fun leaveFamily() = Unit
    override suspend fun currentFamily() = error("unused")
    override suspend fun renameFamily(name: String) = error("unused")
    override suspend fun resetInvite() = error("unused")
    override suspend fun removeMember(userId: String) = Unit
    override suspend fun transferAdmin(userId: String) = error("unused")
    override suspend fun createItem(draft: ItemDraft, today: String) = error("unused")
    override suspend fun patchItem(id: String, baseRev: Long, fields: JsonObject) = error("unused")
    override suspend fun batch(ids: List<String>, action: String) = BatchOutcome(null, 6)
    override suspend fun undo(token: String) = UndoOutcome()
    override suspend fun sync(since: Long): SyncPage {
        syncError?.let { throw it }
        return page
    }
    override suspend fun barcode(code: String) = BarcodeHit(found = false)
    override suspend fun uploadPhoto(itemId: String, bytes: ByteArray) = error("unused")
    override suspend fun photoThumb(photoId: String) = error("unused")
    override suspend fun updateNotifyTime(time: String) {
        notifyError?.let { throw it }
        savedNotify = time
    }
    override fun openSocket(onText: (String) -> Unit, onClosed: () -> Unit): () -> Unit = {}
}
