package com.damy.pantry.data

import com.pantrykeeper.core.model.FieldLimits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.ZoneId

class PantryRepository(
    private val api: PantryApi,
    private val cache: ItemCache,
    val session: SessionStore,
    private val scope: CoroutineScope,
    private val today: () -> LocalDate = { shanghaiToday() },
    private val io: CoroutineContext = Dispatchers.IO,
) {
    val items = MutableStateFlow<List<CachedItem>>(emptyList())
    val family = MutableStateFlow<Family?>(null)
    val online = MutableStateFlow(true)
    val notice = MutableStateFlow<String?>(null)
    val thumbs = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    val events = MutableSharedFlow<AppEvent>(extraBufferCapacity = 16)

    @Volatile private var foreground = false
    @Volatile private var networkUp = true
    private val serverDown = MutableStateFlow(false)
    private var poll: Job? = null
    private var closeSocket: (() -> Unit)? = null

    fun setOnline(value: Boolean) {
        networkUp = value
        online.value = value && !serverDown.value
        if (!serverDown.value && value) notice.value = null
    }

    private fun markServerDown() {
        serverDown.value = true
        online.value = false
        notice.value = "暂时连不上服务器"
    }

    private fun markServerUp() {
        serverDown.value = false
        notice.value = null
        online.value = networkUp
    }

    fun restoreFromCache() {
        items.value = cache.snapshot()
        family.value = session.loadFamily()?.let { runCatching { pantryJson.decodeFromString(Family.serializer(), it) }.getOrNull() }
    }

    suspend fun boot() {
        restoreFromCache()
        if (session.token == null || !online.value) return
        try {
            guard {
                val me = api.me()
                session.userId = me.userId
                session.displayName = me.displayName
                if (me.familyId == null) wipeFamilyCache() else pull()
            }
        } catch (_: java.io.IOException) {
            markServerDown()
        } catch (_: PantryCallException) {
            // 401 and not_in_family already wiped inside guard.
        }
    }

    fun startForeground() {
        if (foreground) return
        foreground = true
        poll = scope.launch {
            var wait = 5_000L
            while (isActive && foreground) {
                if (session.token != null && serverDown.value && networkUp) {
                    val recovered = try {
                        guard { api.me() }
                        session.token != null
                    } catch (_: java.io.IOException) {
                        false
                    } catch (_: PantryCallException) {
                        false
                    }
                    if (recovered) {
                        markServerUp()
                        runCatching { pull() }
                        wait = 5_000L
                    } else {
                        wait = (wait * 2).coerceAtMost(30_000L)
                    }
                } else if (online.value && session.token != null) {
                    ensureSocket()
                    val failed = runCatching { pull() }
                    if (failed.exceptionOrNull() is java.io.IOException) markServerDown()
                    wait = 5_000L
                }
                delay(wait)
            }
        }
    }

    fun stopForeground() {
        foreground = false
        poll?.cancel()
        poll = null
        closeSocket?.invoke()
        closeSocket = null
    }

    suspend fun requestCode(phone: String) = online {
        api.requestCode(phone)
    }

    suspend fun login(phone: String, code: String) = online {
        val result = api.login(phone, code)
        wipeFamilyCache()
        session.token = result.token
        session.userId = result.userId
        session.displayName = result.displayName
        session.phoneMask = maskPhone(phone)
        val me = api.me()
        if (me.familyId != null) pull() else wipeFamilyCache()
    }

    suspend fun logout() {
        stopForeground()
        if (session.token != null) runCatching { api.logout() }
        session.clear()
        publish(emptyList())
        cache.setCursor(0)
        family.value = null
        thumbs.value = emptyMap()
        PendingPhoto.clear()
    }

    suspend fun createFamily(name: String): Family = online {
        FieldLimits.familyNameError(name)?.let { throw PantryCallException(400, "invalid_name", it) }
        val created = api.createFamily(name.trim())
        remember(created)
        pull()
        created
    }

    suspend fun joinFamily(code: String): Family = online {
        val normalized = normalizeInvite(code) ?: throw PantryCallException(404, "invite_invalid", "邀请码无效，请核对后再试")
        val joined = api.joinFamily(normalized)
        remember(joined)
        pull()
        joined
    }

    suspend fun updateDisplayName(name: String): Me = online {
        FieldLimits.displayNameError(name)?.let { throw PantryCallException(400, "invalid_name", it) }
        val me = api.updateDisplayName(name.trim())
        session.displayName = me.displayName
        me
    }

    suspend fun renameFamily(name: String): Family = online {
        FieldLimits.familyNameError(name)?.let { throw PantryCallException(400, "invalid_name", it) }
        remember(api.renameFamily(name.trim()))
    }

    suspend fun resetInvite(): Family = online { remember(api.resetInvite()) }

    suspend fun removeMember(userId: String) = online { api.removeMember(userId); remember(api.currentFamily()) }

    suspend fun transferAdmin(userId: String): Family = online { remember(api.transferAdmin(userId)) }

    suspend fun leaveFamily() = online {
        api.leaveFamily()
        wipeFamilyCache()
    }

    suspend fun saveNew(draft: ItemDraft): String = online {
        validate(draft)
        val created = api.createItem(draft, today().toString())
        pull()
        created.id
    }

    suspend fun uploadPhoto(itemId: String, bytes: ByteArray): CachedItem = online {
        val saved = api.uploadPhoto(itemId, bytes)
        saved.photoId?.let { rememberThumb(it, bytes) }
        pull()
        saved
    }

    fun rememberThumb(photoId: String, bytes: ByteArray) {
        thumbs.value = thumbs.value + (photoId to bytes)
    }

    suspend fun ensureThumb(photoId: String) {
        if (photoId in thumbs.value || session.token == null) return
        val bytes = runCatching { api.photoThumb(photoId) }.getOrNull() ?: return
        rememberThumb(photoId, bytes)
    }

    suspend fun saveEdit(original: CachedItem, draft: ItemDraft): String? = online {
        validate(draft)
        val fields = diff(original, draft)
        if (fields.isEmpty()) return@online null
        val result = api.patchItem(original.id, original.rev, fields)
        pull()
        result.message
    }

    suspend fun batch(ids: List<String>, action: String): BatchOutcome = online {
        val result = api.batch(ids, action)
        pull()
        result
    }

    suspend fun undo(token: String): UndoOutcome = online {
        val result = api.undo(token)
        pull()
        result
    }

    suspend fun barcode(code: String) = api.barcode(code)

    suspend fun pull() {
        if (!online.value || session.token == null) return
        guard {
            val page = api.sync(cache.cursor())
            if (page.reset && cache.cursor() != 0L) {
                cache.setCursor(0)
                publish(emptyList())
                apply(api.sync(0))
            } else {
                apply(page)
            }
        }
    }

    private fun apply(page: SyncPage) {
        val merged = SyncMerger.merge(cache.snapshot(), page.items, page.tombstones.map { it.itemId }.toSet())
        publish(merged)
        cache.setCursor(page.rev)
        page.family?.let { remember(it) }
    }

    private fun publish(rows: List<CachedItem>) {
        cache.replace(rows)
        items.value = rows
    }

    private fun remember(value: Family): Family {
        family.value = value
        session.saveFamily(pantryJson.encodeToString(Family.serializer(), value))
        return value
    }

    private fun ensureSocket() {
        if (closeSocket != null || session.token == null) return
        closeSocket = api.openSocket(
            onText = { text -> scope.launch { handleSocket(text) } },
            onClosed = { closeSocket = null },
        )
    }

    internal suspend fun handleSocket(text: String) {
        when {
            "session_revoked" in text -> {
                stopForeground()
                session.clear()
                wipeFamilyCache()
                events.emit(AppEvent.Revoked)
            }
            "removed_from_family" in text -> {
                wipeFamilyCache()
                events.emit(AppEvent.Removed)
            }
            "changed" in text -> runCatching { pull() }
        }
    }

    private suspend fun <T> online(block: suspend () -> T): T {
        if (!online.value) throw OfflineException()
        return guard(block)
    }

    private suspend fun <T> guard(block: suspend () -> T): T = try {
        withContext(io) { block() }
    } catch (e: PantryCallException) {
        if (e.status == 401) {
            stopForeground()
            session.clear()
            wipeFamilyCache()
            events.emit(AppEvent.Revoked)
        } else if (e.status == 403 && e.code == "not_in_family") {
            wipeFamilyCache()
            events.emit(AppEvent.Removed)
        }
        throw e
    }

    private fun wipeFamilyCache() {
        closeSocket?.invoke()
        closeSocket = null
        cache.replace(emptyList())
        cache.setCursor(0)
        session.saveFamily(null)
        items.value = emptyList()
        family.value = null
        thumbs.value = emptyMap()
        PendingPhoto.clear()
    }

    private fun validate(draft: ItemDraft) {
        FieldLimits.itemNameError(draft.name)?.let { throw PantryCallException(400, "invalid_name", it) }
        FieldLimits.noteError(draft.note)?.let { throw PantryCallException(400, "invalid_note", it) }
        FieldLimits.locationError(draft.location)?.let { throw PantryCallException(400, "invalid_location", it) }
        if (draft.expiryDate.isNullOrBlank() && (draft.shelfLifeValue == null || draft.shelfLifeUnit == null)) {
            throw PantryCallException(400, "expiry_required", "需要到期日或可推算的保质期")
        }
    }

    private fun diff(original: CachedItem, draft: ItemDraft): JsonObject = buildJsonObject {
        if (draft.name.trim() != original.name) put("name", draft.name.trim())
        if (draft.category != original.category) put("category", draft.category)
        if (draft.quantity != original.quantity) put("quantity", draft.quantity)
        val location = draft.location?.trim()?.ifEmpty { null }
        if (location != original.location) {
            if (location == null) put("location", JsonNull) else put("location", location)
        }
        val note = draft.note?.trim()?.ifEmpty { null }
        if (note != original.note) {
            if (note == null) put("note", JsonNull) else put("note", note)
        }
        if (draft.expiryDate != null && draft.expiryDate != original.expiryDate) put("expiry_date", draft.expiryDate)
        if (draft.reminderTiers != original.reminderTiers) {
            put("reminder_tiers", buildJsonArray { draft.reminderTiers.forEach { add(JsonPrimitive(it)) } })
        }
    }
}

object SyncMerger {
    fun merge(local: List<CachedItem>, incoming: List<CachedItem>, tombstoneIds: Set<String>): List<CachedItem> {
        val map = local.associateBy { it.id }.toMutableMap()
        incoming.forEach { item ->
            val prev = map[item.id]
            if (prev == null || item.rev >= prev.rev) map[item.id] = item
        }
        tombstoneIds.forEach { map.remove(it) }
        return map.values.toList()
    }
}

fun shanghaiToday(): LocalDate {
    val date = java.time.LocalDate.now(ZoneId.of("Asia/Shanghai"))
    return LocalDate(date.year, date.monthValue, date.dayOfMonth)
}
