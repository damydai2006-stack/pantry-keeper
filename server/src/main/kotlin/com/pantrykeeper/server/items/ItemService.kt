package com.pantrykeeper.server.items

import com.pantrykeeper.core.date.DateMath
import com.pantrykeeper.core.date.ShelfUnit
import com.pantrykeeper.core.model.FieldLimits
import com.pantrykeeper.core.reminder.Tier
import com.pantrykeeper.server.family.FamilyAccess
import com.pantrykeeper.server.family.orThrow
import com.pantrykeeper.server.http.ApiException
import com.pantrykeeper.server.sync.FamilyHub
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.uuid.Uuid

class ItemService(
    private val clock: Clock,
    private val hub: FamilyHub,
    private val photos: PhotoService,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun create(userId: Uuid, familyId: Uuid?, request: CreateItemRequest): ItemDto {
        val family = requireFamily(familyId)
        val id = request.id?.let { parseId(it) } ?: Uuid.random()
        val existing = transaction { findItem(id) }
        if (existing != null) {
            FamilyAccess.decide(family, existing.familyId).orThrow()
            return existing.toDto()
        }
        val draft = normalizeCreate(request)
        val saved = transaction {
            lockFamily(family)
            val again = findItem(id)
            if (again != null) {
                FamilyAccess.decide(family, again.familyId).orThrow()
                return@transaction again.toDto()
            }
            val rev = nextRev()
            val now = now()
            val meta = draft.touched.associateWith { FieldMeta(rev, userId.toString()) }
            insertItem(id, family, userId, draft, rev, now, meta)
            writeEvent(family, id, userId, "create", draft.touched)
            loadItem(id, family)!!.toDto()
        }
        hub.changed(family, saved.rev)
        return saved
    }

    fun get(userId: Uuid, familyId: Uuid?, rawId: String): ItemDto {
        val family = requireFamily(familyId)
        val id = parseId(rawId)
        return transaction {
            val row = findItem(id)
            if (row != null) {
                FamilyAccess.decide(family, row.familyId).orThrow()
                return@transaction row.toDto()
            }
            tombstone(id, family)?.let { throw deleted(it) }
            throw ApiException(HttpStatusCode.NotFound, "not_found", "未找到")
        }
    }

    fun list(familyId: Uuid?, status: String?): List<ItemDto> {
        val family = requireFamily(familyId)
        if (status != null && status !in STATUSES) throw ApiException(HttpStatusCode.BadRequest, "invalid_status", "状态不正确")
        return transaction {
            val sql = buildString {
                append("select * from items where family_id = ?")
                if (status != null) append(" and status = ?")
                append(" order by expiry_date, created_at")
            }
            jdbc().prepareStatement(sql).use { ps ->
                ps.setObject(1, family.toJava())
                if (status != null) ps.setString(2, status)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<ItemDto>()
                    while (rs.next()) out += readItem(rs).toDto()
                    out
                }
            }
        }
    }

    fun patch(userId: Uuid, familyId: Uuid?, rawId: String, baseRev: Long, fields: JsonObject): PatchResult {
        val family = requireFamily(familyId)
        val id = parseId(rawId)
        val result = transaction {
            lockFamily(family)
            val row = findForFamily(id, family)
            if (row == null) {
                tombstone(id, family)?.let { throw deleted(it) }
                throw ApiException(HttpStatusCode.NotFound, "not_found", "未找到")
            }
            val changes = normalizePatch(fields)
            if (changes.isEmpty()) return@transaction PatchResult(row.toDto(), emptyList())
            val overwritten = mutableListOf<Overwritten>()
            val meta = row.meta.toMutableMap()
            changes.forEach { (field, _) ->
                val prior = meta[field]
                if (prior != null && prior.rev > baseRev && prior.by != userId.toString()) {
                    overwritten += Overwritten(field, displayName(Uuid.parse(prior.by)))
                }
            }
            val rev = nextRev()
            val now = now()
            changes.keys.forEach { meta[it] = FieldMeta(rev, userId.toString()) }
            var merged = row.apply(changes)
            if ("status" in changes) merged = merged.copy(statusChangedAt = now, statusChangedBy = userId)
            updateItem(merged, rev, userId, now, meta)
            writeEvent(family, id, userId, "update", changes.keys)
            PatchResult(loadItem(id, family)!!.toDto(), overwritten)
        }
        hub.changed(family, result.item.rev)
        return result
    }

    fun batch(userId: Uuid, familyId: Uuid?, ids: List<String>, action: String): BatchResult {
        val family = requireFamily(familyId)
        if (action !in ACTIONS) throw ApiException(HttpStatusCode.BadRequest, "invalid_action", "批量操作不正确")
        if (ids.isEmpty()) throw ApiException(HttpStatusCode.BadRequest, "empty_batch", "没有选中物品")
        val parsed = ids.map { parseId(it) }
        val result = transaction {
            lockFamily(family)
            val rows = parsed.map { id ->
                findForFamily(id, family) ?: throw ApiException(HttpStatusCode.NotFound, "not_found", "未找到")
            }
            val rev = nextRev()
            val now = now()
            if (action == "delete") {
                rows.forEach { row ->
                    photos.deleteStored(row.photoId, family)
                    update("delete from items where id = ? and family_id = ?", {
                        it.setObject(1, row.id.toJava())
                        it.setObject(2, family.toJava())
                    })
                    update(
                        "insert into item_tombstones (item_id, family_id, deleted_by, deleted_at, rev) values (?, ?, ?, ?, ?)",
                    ) {
                        it.setObject(1, row.id.toJava())
                        it.setObject(2, family.toJava())
                        it.setObject(3, userId.toJava())
                        it.setObject(4, now)
                        it.setLong(5, rev)
                    }
                    writeEvent(family, row.id, userId, "delete", setOf("status"))
                }
                return@transaction BatchResult(undoToken = null, rev = rev, skipped = 0)
            }
            val prior = rows.map { PriorItem(it.id.toString(), it.status) }
            val nextStatus = when (action) {
                "restore" -> "in_stock"
                else -> action
            }
            rows.forEach { row ->
                val meta = row.meta.toMutableMap()
                meta["status"] = FieldMeta(rev, userId.toString())
                update(
                    "update items set status = ?, status_changed_at = ?, status_changed_by = ?, updated_by = ?, updated_at = ?, rev = ?, field_meta = ?::jsonb where id = ? and family_id = ?",
                ) {
                    it.setString(1, nextStatus)
                    it.setObject(2, now)
                    it.setObject(3, userId.toJava())
                    it.setObject(4, userId.toJava())
                    it.setObject(5, now)
                    it.setLong(6, rev)
                    it.setString(7, encodeMeta(meta))
                    it.setObject(8, row.id.toJava())
                    it.setObject(9, family.toJava())
                }
                writeEvent(family, row.id, userId, "status", setOf("status"))
            }
            val token = Uuid.random()
            update(
                "insert into batch_ops (undo_token, family_id, user_id, action, prior, created_at) values (?, ?, ?, ?, ?::jsonb, ?)",
            ) {
                it.setObject(1, token.toJava())
                it.setObject(2, family.toJava())
                it.setObject(3, userId.toJava())
                it.setString(4, action)
                it.setString(5, json.encodeToString(PriorBatch.serializer(), PriorBatch(rev, prior)))
                it.setObject(6, now)
            }
            BatchResult(undoToken = token.toString(), rev = rev, skipped = 0)
        }
        hub.changed(family, result.rev)
        return result
    }

    fun undo(userId: Uuid, familyId: Uuid?, rawToken: String): UndoResult {
        val family = requireFamily(familyId)
        val token = parseId(rawToken)
        val result = transaction {
            lockFamily(family)
            val op = queryOne(
                "select family_id, prior::text, created_at from batch_ops where undo_token = ?",
                { it.setObject(1, token.toJava()) },
            ) { rs -> Triple(rs.getObject(1, java.util.UUID::class.java).toKotlin(), rs.getString(2), rs.getObject(3, OffsetDateTime::class.java)) }
                ?: throw ApiException(HttpStatusCode.NotFound, "not_found", "未找到")
            FamilyAccess.decide(family, op.first).orThrow()
            val created = op.third
            if (!now().isBefore(created.plusSeconds(60))) {
                throw ApiException(HttpStatusCode.Gone, "undo_expired", "撤销已超过时限")
            }
            val prior = json.decodeFromString(PriorBatch.serializer(), op.second)
            var skipped = 0
            var restored = 0
            val rev = nextRev()
            val now = now()
            prior.items.forEach { item ->
                val id = Uuid.parse(item.id)
                val row = findForFamily(id, family)
                val statusMeta = row?.meta?.get("status")
                val changedByOther = statusMeta != null && statusMeta.rev > prior.batchRev && statusMeta.by != userId.toString()
                if (row == null || changedByOther) {
                    skipped += 1
                    return@forEach
                }
                val meta = row.meta.toMutableMap()
                meta["status"] = FieldMeta(rev, userId.toString())
                update(
                    "update items set status = ?, status_changed_at = ?, status_changed_by = ?, updated_by = ?, updated_at = ?, rev = ?, field_meta = ?::jsonb where id = ? and family_id = ?",
                ) {
                    it.setString(1, item.status)
                    it.setObject(2, now)
                    it.setObject(3, userId.toJava())
                    it.setObject(4, userId.toJava())
                    it.setObject(5, now)
                    it.setLong(6, rev)
                    it.setString(7, encodeMeta(meta))
                    it.setObject(8, id.toJava())
                    it.setObject(9, family.toJava())
                }
                restored += 1
            }
            UndoResult(skipped, restored, rev)
        }
        hub.changed(family, result.rev)
        return result
    }

    fun head(familyId: Uuid): Long = transaction {
        val items = queryOne("select coalesce(max(rev), 0) from items where family_id = ?", { it.setObject(1, familyId.toJava()) }) { it.getLong(1) } ?: 0L
        val tombs = queryOne("select coalesce(max(rev), 0) from item_tombstones where family_id = ?", { it.setObject(1, familyId.toJava()) }) { it.getLong(1) } ?: 0L
        maxOf(items, tombs)
    }

    fun sync(userId: Uuid, familyId: Uuid?, since: Long): SyncPayload {
        val family = requireFamily(familyId)
        if (since < 0) throw ApiException(HttpStatusCode.BadRequest, "invalid_cursor", "游标不正确")
        return transaction {
            purgeOldTombstones(family)
            val horizon = queryOne(
                "select sync_horizon from families where id = ?",
                { it.setObject(1, family.toJava()) },
            ) { it.getLong(1) } ?: 0L
            val head = queryOne("select coalesce(max(rev), 0) from items where family_id = ?", { it.setObject(1, family.toJava()) }) { it.getLong(1) } ?: 0L
            val tombHead = queryOne("select coalesce(max(rev), 0) from item_tombstones where family_id = ?", { it.setObject(1, family.toJava()) }) { it.getLong(1) } ?: 0L
            val rev = maxOf(head, tombHead)
            if (since > 0 && since < horizon) {
                return@transaction SyncPayload(reset = true, rev = rev, items = emptyList(), tombstones = emptyList(), family = null, notifyTime = null)
            }
            val items = mutableListOf<ItemDto>()
            val itemSql = if (since == 0L) {
                "select * from items where family_id = ? order by rev"
            } else {
                "select * from items where family_id = ? and rev > ? order by rev"
            }
            jdbc().prepareStatement(itemSql).use { ps ->
                ps.setObject(1, family.toJava())
                if (since != 0L) ps.setLong(2, since)
                ps.executeQuery().use { rs -> while (rs.next()) items += readItem(rs).toDto() }
            }
            val tombs = mutableListOf<TombstoneDto>()
            val tombSql = if (since == 0L) {
                "select item_id, deleted_by, deleted_at, rev from item_tombstones where family_id = ? order by rev"
            } else {
                "select item_id, deleted_by, deleted_at, rev from item_tombstones where family_id = ? and rev > ? order by rev"
            }
            jdbc().prepareStatement(tombSql).use { ps ->
                ps.setObject(1, family.toJava())
                if (since != 0L) ps.setLong(2, since)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val by = rs.getObject(2, java.util.UUID::class.java)?.toKotlin()
                        tombs += TombstoneDto(
                            itemId = rs.getObject(1, java.util.UUID::class.java).toKotlin().toString(),
                            deletedBy = by?.let { displayName(it) },
                            deletedAt = rs.getObject(3, OffsetDateTime::class.java).toString(),
                            rev = rs.getLong(4),
                        )
                    }
                }
            }
            val notify = queryOne("select notify_time::text from user_settings where user_id = ?", { it.setObject(1, userId.toJava()) }) { it.getString(1)?.take(5) }
            SyncPayload(reset = false, rev = rev, items = items, tombstones = tombs, family = null, notifyTime = notify)
        }
    }

    private fun purgeOldTombstones(family: Uuid) {
        val cutoff = now().minusDays(90)
        val purged = mutableListOf<Long>()
        jdbc().prepareStatement("select rev from item_tombstones where family_id = ? and deleted_at < ?").use { ps ->
            ps.setObject(1, family.toJava())
            ps.setObject(2, cutoff)
            ps.executeQuery().use { rs -> while (rs.next()) purged += rs.getLong(1) }
        }
        if (purged.isEmpty()) return
        update("delete from item_tombstones where family_id = ? and deleted_at < ?") {
            it.setObject(1, family.toJava())
            it.setObject(2, cutoff)
        }
        val horizon = purged.max()
        update("update families set sync_horizon = greatest(sync_horizon, ?) where id = ?") {
            it.setLong(1, horizon)
            it.setObject(2, family.toJava())
        }
    }

    private fun requireFamily(familyId: Uuid?): Uuid =
        familyId ?: throw ApiException(HttpStatusCode.Forbidden, "not_in_family", "不在家庭中")

    private fun lockFamily(family: Uuid) {
        queryOne("select id from families where id = ? for update", { it.setObject(1, family.toJava()) }) { it.getObject(1) }
            ?: throw ApiException(HttpStatusCode.Forbidden, "not_in_family", "不在家庭中")
    }

    private fun nextRev(): Long = queryOne("select nextval('item_rev_seq')", {}) { it.getLong(1) } ?: error("rev")

    private fun now(): OffsetDateTime = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

    private fun today(requestToday: String?): LocalDate =
        requestToday?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: LocalDate.parse(now().toLocalDate().toString())

    private fun normalizeCreate(request: CreateItemRequest): Draft {
        FieldLimits.itemNameError(request.name)?.let { throw ApiException(HttpStatusCode.BadRequest, "invalid_name", it) }
        val location = blankToNull(request.location)
        val note = blankToNull(request.note)
        FieldLimits.locationError(location)?.let { throw ApiException(HttpStatusCode.BadRequest, "invalid_location", it) }
        FieldLimits.noteError(note)?.let { throw ApiException(HttpStatusCode.BadRequest, "invalid_note", it) }
        val category = mapCategory(request.category ?: "food")
        val quantity = request.quantity ?: 1
        if (quantity < 0) throw ApiException(HttpStatusCode.BadRequest, "invalid_quantity", "数量不能为负")
        val tiers = request.reminderTiers?.let { parseTiers(it) } ?: listOf("D30", "D5")
        val expiry = resolveExpiry(request.expiryDate, request.productionDate, request.shelfLifeValue, request.shelfLifeUnit, request.expiryBasis, today(request.clientToday))
        return Draft(
            name = request.name.trim(),
            category = category,
            quantity = quantity,
            location = location,
            note = note,
            barcode = blankToNull(request.barcode),
            expiry = expiry.date,
            production = request.productionDate?.let { parseDate(it) },
            shelfValue = request.shelfLifeValue,
            shelfUnit = request.shelfLifeUnit,
            basis = expiry.basis,
            opened = request.openedDate?.let { parseDate(it) },
            afterOpenDays = request.afterOpenDays,
            tiers = tiers,
            touched = setOf("name", "category", "quantity", "expiry_date", "expiry_basis", "reminder_tiers"),
        )
    }

    private fun normalizePatch(fields: JsonObject): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        fun raw(key: String) = fields[key]
        if ("name" in fields) {
            val name = raw("name")?.jsonPrimitive?.content ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_name", "物品名需要 1 到 40 个字")
            FieldLimits.itemNameError(name)?.let { throw ApiException(HttpStatusCode.BadRequest, "invalid_name", it) }
            out["name"] = name.trim()
        }
        if ("category" in fields) out["category"] = mapCategory(raw("category")?.jsonPrimitive?.content)
        if ("quantity" in fields) {
            val q = raw("quantity")?.jsonPrimitive?.content?.toIntOrNull() ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_quantity", "数量不正确")
            if (q < 0) throw ApiException(HttpStatusCode.BadRequest, "invalid_quantity", "数量不能为负")
            out["quantity"] = q
        }
        if ("location" in fields) {
            val location = raw("location")?.jsonPrimitive?.contentOrNull()?.let { blankToNull(it) }
            FieldLimits.locationError(location)?.let { throw ApiException(HttpStatusCode.BadRequest, "invalid_location", it) }
            out["location"] = location
        }
        if ("note" in fields) {
            val note = raw("note")?.jsonPrimitive?.contentOrNull()?.let { blankToNull(it) }
            FieldLimits.noteError(note)?.let { throw ApiException(HttpStatusCode.BadRequest, "invalid_note", it) }
            out["note"] = note
        }
        if ("barcode" in fields) out["barcode"] = raw("barcode")?.jsonPrimitive?.contentOrNull()?.let { blankToNull(it) }
        if ("expiry_date" in fields) out["expiry_date"] = parseDate(raw("expiry_date")?.jsonPrimitive?.content ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_date", "日期不正确"))
        if ("production_date" in fields) {
            val text = raw("production_date")?.jsonPrimitive?.contentOrNull()
            out["production_date"] = text?.let { parseDate(it) }
        }
        if ("shelf_life_value" in fields) out["shelf_life_value"] = raw("shelf_life_value")?.jsonPrimitive?.contentOrNull()?.toIntOrNull()
        if ("shelf_life_unit" in fields) out["shelf_life_unit"] = raw("shelf_life_unit")?.jsonPrimitive?.contentOrNull()
        if ("expiry_basis" in fields) {
            val basis = raw("expiry_basis")?.jsonPrimitive?.content
            if (basis !in BASES) throw ApiException(HttpStatusCode.BadRequest, "invalid_basis", "起算方式不正确")
            out["expiry_basis"] = basis
        }
        if ("opened_date" in fields) out["opened_date"] = raw("opened_date")?.jsonPrimitive?.contentOrNull()?.let { parseDate(it) }
        if ("after_open_days" in fields) out["after_open_days"] = raw("after_open_days")?.jsonPrimitive?.contentOrNull()?.toIntOrNull()
        if ("reminder_tiers" in fields) {
            val arr = fields["reminder_tiers"]
            val tiers = if (arr == null || arr.toString() == "null") emptyList() else {
                json.decodeFromString<List<String>>(arr.toString()).let { parseTiers(it) }
            }
            out["reminder_tiers"] = tiers
        }
        if ("status" in fields) {
            val status = raw("status")?.jsonPrimitive?.content
            if (status !in STATUSES) throw ApiException(HttpStatusCode.BadRequest, "invalid_status", "状态不正确")
            out["status"] = status
        }
        return out
    }

    private fun resolveExpiry(expiry: String?, production: String?, value: Int?, unit: String?, basis: String?, today: LocalDate): ResolvedExpiry {
        if (expiry != null) {
            return ResolvedExpiry(parseDate(expiry), basis ?: "manual_date")
        }
        if (value == null || unit == null) throw ApiException(HttpStatusCode.BadRequest, "expiry_required", "需要到期日或可推算的保质期")
        val shelf = shelfUnit(unit)
        if (!DateMath.shelfAllowed(value, shelf)) throw ApiException(HttpStatusCode.BadRequest, "invalid_shelf", "保质期需要在 1 天到 10 年之间")
        val start = production?.let { parseDate(it) } ?: today
        val computed = DateMath.addShelf(start, value, shelf)
        val usedBasis = basis ?: if (production != null) "manual_duration_from_prod" else "manual_duration_from_today"
        return ResolvedExpiry(computed, usedBasis)
    }

    private fun parseDate(text: String): LocalDate =
        runCatching { LocalDate.parse(text) }.getOrElse { throw ApiException(HttpStatusCode.BadRequest, "invalid_date", "日期不正确") }

    private fun shelfUnit(raw: String): ShelfUnit = when (raw) {
        "day" -> ShelfUnit.DAY
        "month" -> ShelfUnit.MONTH
        "year" -> ShelfUnit.YEAR
        else -> throw ApiException(HttpStatusCode.BadRequest, "invalid_shelf", "保质期单位不正确")
    }

    private fun mapCategory(raw: String?): String = when (raw) {
        null, "", "food", "食品" -> "food"
        "medicine", "药品" -> "medicine"
        "condiment", "调料" -> "condiment"
        "other", "其他" -> "other"
        else -> throw ApiException(HttpStatusCode.BadRequest, "invalid_category", "分类不正确")
    }

    private fun parseTiers(raw: List<String>): List<String> {
        val parsed = raw.map { Tier.parse(it)?.name ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_tier", "提醒档位不正确") }
        return parsed.distinct()
    }

    private fun parseId(raw: String): Uuid =
        runCatching { Uuid.parse(raw) }.getOrElse { throw ApiException(HttpStatusCode.NotFound, "not_found", "未找到") }

    private fun displayName(userId: Uuid): String =
        queryOne("select display_name from users where id = ?", { it.setObject(1, userId.toJava()) }) { it.getString(1) } ?: "成员"

    private fun deleted(row: TombRow): ApiException =
        ApiException(HttpStatusCode.Gone, "item_deleted", "已被${row.byName}删除")

    private fun tombstone(id: Uuid, family: Uuid): TombRow? = queryOne(
        "select deleted_by from item_tombstones where item_id = ? and family_id = ?",
        {
            it.setObject(1, id.toJava())
            it.setObject(2, family.toJava())
        },
    ) { rs ->
        val by = rs.getObject(1, java.util.UUID::class.java)?.toKotlin()
        TombRow(by?.let { displayName(it) } ?: "成员")
    }

    private fun findItem(id: Uuid): ItemRow? = queryOne("select * from items where id = ?", { it.setObject(1, id.toJava()) }) { readItem(it) }

    private fun findForFamily(id: Uuid, family: Uuid): ItemRow? = queryOne(
        "select * from items where id = ? and family_id = ?",
        {
            it.setObject(1, id.toJava())
            it.setObject(2, family.toJava())
        },
    ) { readItem(it) }

    private fun loadItem(id: Uuid, family: Uuid): ItemRow? = findForFamily(id, family)

    private fun insertItem(id: Uuid, family: Uuid, userId: Uuid, draft: Draft, rev: Long, now: OffsetDateTime, meta: Map<String, FieldMeta>) {
        update(
            """
            insert into items (
              id, family_id, name, category, quantity, location, note, barcode, expiry_date, production_date,
              shelf_life_value, shelf_life_unit, expiry_basis, opened_date, after_open_days, reminder_tiers,
              status, field_meta, created_by, created_at, updated_by, updated_at, rev
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'in_stock', ?::jsonb, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ) { ps ->
            ps.setObject(1, id.toJava())
            ps.setObject(2, family.toJava())
            ps.setString(3, draft.name)
            ps.setString(4, draft.category)
            ps.setInt(5, draft.quantity)
            ps.setString(6, draft.location)
            ps.setString(7, draft.note)
            ps.setString(8, draft.barcode)
            ps.setObject(9, java.sql.Date.valueOf(draft.expiry.toString()))
            setDate(ps, 10, draft.production)
            if (draft.shelfValue == null) ps.setNull(11, Types.INTEGER) else ps.setInt(11, draft.shelfValue)
            ps.setString(12, draft.shelfUnit)
            ps.setString(13, draft.basis)
            setDate(ps, 14, draft.opened)
            if (draft.afterOpenDays == null) ps.setNull(15, Types.INTEGER) else ps.setInt(15, draft.afterOpenDays)
            ps.setArray(16, jdbc().createArrayOf("text", draft.tiers.toTypedArray()))
            ps.setString(17, encodeMeta(meta))
            ps.setObject(18, userId.toJava())
            ps.setObject(19, now)
            ps.setObject(20, userId.toJava())
            ps.setObject(21, now)
            ps.setLong(22, rev)
        }
    }

    private fun updateItem(row: ItemRow, rev: Long, userId: Uuid, now: OffsetDateTime, meta: Map<String, FieldMeta>) {
        update(
            """
            update items set name = ?, category = ?, quantity = ?, location = ?, note = ?, barcode = ?, expiry_date = ?,
              production_date = ?, shelf_life_value = ?, shelf_life_unit = ?, expiry_basis = ?, opened_date = ?,
              after_open_days = ?, reminder_tiers = ?, status = ?, field_meta = ?::jsonb, updated_by = ?, updated_at = ?, rev = ?,
              status_changed_at = ?, status_changed_by = ?
            where id = ? and family_id = ?
            """.trimIndent(),
        ) { ps ->
            ps.setString(1, row.name)
            ps.setString(2, row.category)
            ps.setInt(3, row.quantity)
            ps.setString(4, row.location)
            ps.setString(5, row.note)
            ps.setString(6, row.barcode)
            ps.setObject(7, java.sql.Date.valueOf(row.expiry.toString()))
            setDate(ps, 8, row.production)
            if (row.shelfValue == null) ps.setNull(9, Types.INTEGER) else ps.setInt(9, row.shelfValue)
            ps.setString(10, row.shelfUnit)
            ps.setString(11, row.basis)
            setDate(ps, 12, row.opened)
            if (row.afterOpenDays == null) ps.setNull(13, Types.INTEGER) else ps.setInt(13, row.afterOpenDays)
            ps.setArray(14, jdbc().createArrayOf("text", row.tiers.toTypedArray()))
            ps.setString(15, row.status)
            ps.setString(16, encodeMeta(meta))
            ps.setObject(17, userId.toJava())
            ps.setObject(18, now)
            ps.setLong(19, rev)
            if (row.statusChangedAt == null) ps.setNull(20, Types.TIMESTAMP_WITH_TIMEZONE) else ps.setObject(20, row.statusChangedAt)
            if (row.statusChangedBy == null) ps.setNull(21, Types.OTHER) else ps.setObject(21, row.statusChangedBy.toJava())
            ps.setObject(22, row.id.toJava())
            ps.setObject(23, row.familyId.toJava())
        }
    }

    private fun writeEvent(family: Uuid, item: Uuid, actor: Uuid, action: String, fields: Set<String>) {
        update(
            "insert into item_events (family_id, item_id, actor_id, action, changed_fields, at) values (?, ?, ?, ?, ?, ?)",
        ) {
            it.setObject(1, family.toJava())
            it.setObject(2, item.toJava())
            it.setObject(3, actor.toJava())
            it.setString(4, action)
            it.setArray(5, jdbc().createArrayOf("text", fields.toTypedArray()))
            it.setObject(6, now())
        }
    }

    private fun readItem(rs: ResultSet): ItemRow {
        val tiers = (rs.getArray("reminder_tiers").array as Array<*>).map { it.toString() }
        return ItemRow(
            id = rs.getObject("id", java.util.UUID::class.java).toKotlin(),
            familyId = rs.getObject("family_id", java.util.UUID::class.java).toKotlin(),
            name = rs.getString("name"),
            category = rs.getString("category"),
            quantity = rs.getInt("quantity"),
            location = rs.getString("location"),
            note = rs.getString("note"),
            barcode = rs.getString("barcode"),
            expiry = LocalDate.parse(rs.getDate("expiry_date").toString()),
            production = rs.getDate("production_date")?.let { LocalDate.parse(it.toString()) },
            shelfValue = rs.getObject("shelf_life_value") as Int?,
            shelfUnit = rs.getString("shelf_life_unit"),
            basis = rs.getString("expiry_basis"),
            opened = rs.getDate("opened_date")?.let { LocalDate.parse(it.toString()) },
            afterOpenDays = rs.getObject("after_open_days") as Int?,
            tiers = tiers,
            status = rs.getString("status"),
            photoId = rs.getObject("photo_id", java.util.UUID::class.java)?.toKotlin(),
            meta = decodeMeta(rs.getString("field_meta")),
            rev = rs.getLong("rev"),
            updatedBy = rs.getObject("updated_by", java.util.UUID::class.java)?.toKotlin(),
            statusChangedAt = rs.getObject("status_changed_at", OffsetDateTime::class.java),
            statusChangedBy = rs.getObject("status_changed_by", java.util.UUID::class.java)?.toKotlin(),
        )
    }

    private fun encodeMeta(meta: Map<String, FieldMeta>): String = buildJsonObject {
        meta.forEach { (field, value) ->
            put(field, buildJsonObject {
                put("rev", value.rev)
                put("by", value.by)
            })
        }
    }.toString()

    private fun decodeMeta(raw: String?): Map<String, FieldMeta> {
        if (raw.isNullOrBlank() || raw == "{}") return emptyMap()
        val obj = json.parseToJsonElement(raw).jsonObject
        return obj.mapValues { (_, value) ->
            val o = value.jsonObject
            FieldMeta(o.getValue("rev").jsonPrimitive.content.toLong(), o.getValue("by").jsonPrimitive.content)
        }
    }

    private fun setDate(ps: PreparedStatement, index: Int, date: LocalDate?) {
        if (date == null) ps.setNull(index, Types.DATE) else ps.setObject(index, java.sql.Date.valueOf(date.toString()))
    }

    private fun blankToNull(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    private fun kotlinx.serialization.json.JsonElement.contentOrNull(): String? =
        runCatching { jsonPrimitive.content }.getOrNull()

    companion object {
        val STATUSES = setOf("in_stock", "used_up", "discarded")
        val ACTIONS = setOf("used_up", "discarded", "restore", "delete")
        val BASES = setOf("ocr_expiry", "ocr_prod_plus_life", "manual_duration_from_prod", "manual_duration_from_today", "manual_date")
        val FIELD_LABELS = mapOf(
            "name" to "名称",
            "expiry_date" to "到期日",
            "category" to "分类",
            "quantity" to "数量",
            "location" to "位置",
            "note" to "备注",
            "status" to "状态",
            "reminder_tiers" to "提醒档位",
        )
    }
}

data class FieldMeta(val rev: Long, val by: String)

data class Draft(
    val name: String,
    val category: String,
    val quantity: Int,
    val location: String?,
    val note: String?,
    val barcode: String?,
    val expiry: LocalDate,
    val production: LocalDate?,
    val shelfValue: Int?,
    val shelfUnit: String?,
    val basis: String,
    val opened: LocalDate?,
    val afterOpenDays: Int?,
    val tiers: List<String>,
    val touched: Set<String>,
)

data class ResolvedExpiry(val date: LocalDate, val basis: String)

data class ItemRow(
    val id: Uuid,
    val familyId: Uuid,
    val name: String,
    val category: String,
    val quantity: Int,
    val location: String?,
    val note: String?,
    val barcode: String?,
    val expiry: LocalDate,
    val production: LocalDate?,
    val shelfValue: Int?,
    val shelfUnit: String?,
    val basis: String,
    val opened: LocalDate?,
    val afterOpenDays: Int?,
    val tiers: List<String>,
    val status: String,
    val photoId: Uuid?,
    val meta: Map<String, FieldMeta>,
    val rev: Long,
    val updatedBy: Uuid?,
    val statusChangedAt: OffsetDateTime?,
    val statusChangedBy: Uuid?,
) {
    fun toDto(): ItemDto = ItemDto(
        id = id.toString(),
        name = name,
        category = category,
        quantity = quantity,
        location = location,
        note = note,
        barcode = barcode,
        expiryDate = expiry.toString(),
        productionDate = production?.toString(),
        shelfLifeValue = shelfValue,
        shelfLifeUnit = shelfUnit,
        expiryBasis = basis,
        openedDate = opened?.toString(),
        afterOpenDays = afterOpenDays,
        reminderTiers = tiers,
        status = status,
        photoId = photoId?.toString(),
        rev = rev,
    )

    fun apply(changes: Map<String, Any?>): ItemRow = copy(
        name = changes["name"] as? String ?: name,
        category = changes["category"] as? String ?: category,
        quantity = changes["quantity"] as? Int ?: quantity,
        location = if ("location" in changes) changes["location"] as String? else location,
        note = if ("note" in changes) changes["note"] as String? else note,
        barcode = if ("barcode" in changes) changes["barcode"] as String? else barcode,
        expiry = changes["expiry_date"] as? LocalDate ?: expiry,
        production = if ("production_date" in changes) changes["production_date"] as LocalDate? else production,
        shelfValue = if ("shelf_life_value" in changes) changes["shelf_life_value"] as Int? else shelfValue,
        shelfUnit = if ("shelf_life_unit" in changes) changes["shelf_life_unit"] as String? else shelfUnit,
        basis = changes["expiry_basis"] as? String ?: basis,
        opened = if ("opened_date" in changes) changes["opened_date"] as LocalDate? else opened,
        afterOpenDays = if ("after_open_days" in changes) changes["after_open_days"] as Int? else afterOpenDays,
        tiers = if ("reminder_tiers" in changes) @Suppress("UNCHECKED_CAST") (changes["reminder_tiers"] as List<String>) else tiers,
        status = changes["status"] as? String ?: status,
        statusChangedAt = if ("status" in changes) statusChangedAt else statusChangedAt,
    )
}

data class TombRow(val byName: String)

@kotlinx.serialization.Serializable
data class ItemDto(
    val id: String,
    val name: String,
    val category: String,
    val quantity: Int,
    val location: String? = null,
    val note: String? = null,
    val barcode: String? = null,
    val expiryDate: String,
    val productionDate: String? = null,
    val shelfLifeValue: Int? = null,
    val shelfLifeUnit: String? = null,
    val expiryBasis: String,
    val openedDate: String? = null,
    val afterOpenDays: Int? = null,
    val reminderTiers: List<String>,
    val status: String,
    val photoId: String? = null,
    val rev: Long,
)

@kotlinx.serialization.Serializable
data class CreateItemRequest(
    val id: String? = null,
    val name: String,
    val category: String? = null,
    val quantity: Int? = null,
    val location: String? = null,
    val note: String? = null,
    val barcode: String? = null,
    val expiryDate: String? = null,
    val productionDate: String? = null,
    val shelfLifeValue: Int? = null,
    val shelfLifeUnit: String? = null,
    val expiryBasis: String? = null,
    val openedDate: String? = null,
    val afterOpenDays: Int? = null,
    val reminderTiers: List<String>? = null,
    val clientToday: String? = null,
)

@kotlinx.serialization.Serializable
data class Overwritten(val field: String, val byName: String)

data class PatchResult(val item: ItemDto, val overwritten: List<Overwritten>) {
    fun message(): String? {
        if (overwritten.isEmpty()) return null
        val labels = overwritten.joinToString("、") { ItemService.FIELD_LABELS[it.field] ?: it.field }
        val who = overwritten.first().byName
        return "${labels}已被${who}修改，已用你的修改覆盖"
    }
}

data class BatchResult(val undoToken: String?, val rev: Long, val skipped: Int)

data class UndoResult(val skipped: Int, val restored: Int, val rev: Long) {
    fun message(): String? = if (skipped > 0) "$skipped 件已被他人修改，未撤销" else null
}

@kotlinx.serialization.Serializable
data class PriorItem(val id: String, val status: String)

@kotlinx.serialization.Serializable
data class PriorBatch(val batchRev: Long, val items: List<PriorItem>)

@kotlinx.serialization.Serializable
data class TombstoneDto(val itemId: String, val deletedBy: String?, val deletedAt: String, val rev: Long)

data class SyncPayload(
    val reset: Boolean,
    val rev: Long,
    val items: List<ItemDto>,
    val tombstones: List<TombstoneDto>,
    val family: com.pantrykeeper.server.family.FamilyView?,
    val notifyTime: String?,
)
