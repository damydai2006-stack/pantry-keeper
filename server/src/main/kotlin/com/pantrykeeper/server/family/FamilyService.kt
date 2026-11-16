package com.pantrykeeper.server.family

import com.pantrykeeper.core.model.FieldLimits
import com.pantrykeeper.server.auth.SlidingLimiter
import com.pantrykeeper.server.db.Families
import com.pantrykeeper.server.db.FamilyMembers
import com.pantrykeeper.server.db.Users
import com.pantrykeeper.server.http.ApiException
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.uuid.Uuid

sealed class FamilySignal {
    data class Changed(val familyId: Uuid) : FamilySignal()
    data class Removed(val userId: Uuid, val familyId: Uuid) : FamilySignal()
}

class FamilyService(private val clock: Clock) {
    var signals: (FamilySignal) -> Unit = {}
    private val random = SecureRandom()
    private val joinLimiter = SlidingLimiter(clock, maxHits = 5, window = Duration.ofMinutes(1))

    fun create(userId: Uuid, rawName: String): FamilyView = transaction {
        if (activeMembership(userId) != null) {
            throw ApiException(HttpStatusCode.Conflict, "already_in_family", "已在一个家庭中")
        }
        val name = rawName.trim()
        FieldLimits.familyNameError(name)?.let { throw ApiException(HttpStatusCode.BadRequest, "invalid_name", it) }
        val now = now()
        val id = Uuid.random()
        val code = freshCode()
        Families.insert {
            it[Families.id] = id
            it[Families.name] = name
            it[adminUserId] = userId
            it[inviteCode] = code
            it[inviteExpiresAt] = now.plus(INVITE_TTL)
            it[createdAt] = now
            it[updatedAt] = now
            it[rev] = 1
        }
        FamilyMembers.insert {
            it[familyId] = id
            it[FamilyMembers.userId] = userId
            it[role] = "admin"
            it[joinedAt] = now
        }
        load(id, userId)
    }.also { signals(FamilySignal.Changed(Uuid.parse(it.id))) }

    fun join(userId: Uuid, rawCode: String): FamilyView {
        if (transaction { activeMembership(userId) } != null) {
            throw ApiException(HttpStatusCode.Conflict, "already_in_family", "已在一个家庭中")
        }
        joinLimiter.hit(userId.toString())
        val code = InviteCodes.normalize(rawCode)
            ?: throw ApiException(HttpStatusCode.NotFound, "invite_invalid", "邀请码无效")
        return transaction {
            val now = now()
            val family = Families.selectAll().where { Families.inviteCode eq code }.singleOrNull()
            val open = family != null &&
                family[Families.emptySince] == null &&
                family[Families.inviteExpiresAt]?.let { now.isBefore(it) } == true &&
                countActive(family[Families.id]) > 0L
            if (!open || family == null) {
                throw ApiException(HttpStatusCode.NotFound, "invite_invalid", "邀请码无效")
            }
            try {
                FamilyMembers.insert {
                    it[familyId] = family[Families.id]
                    it[FamilyMembers.userId] = userId
                    it[role] = "member"
                    it[joinedAt] = now
                }
            } catch (e: Exception) {
                if (e.causes().any { it.message?.contains("one_active_family") == true }) {
                    throw ApiException(HttpStatusCode.Conflict, "already_in_family", "已在一个家庭中")
                }
                throw e
            }
            load(family[Families.id], userId)
        }.also { signals(FamilySignal.Changed(Uuid.parse(it.id))) }
    }

    fun leave(userId: Uuid) {
        val familyId = transaction {
            val member = activeMembership(userId)
                ?: throw ApiException(HttpStatusCode.Forbidden, "not_in_family", "不在家庭中")
            val familyId = member[FamilyMembers.familyId]
            val family = Families.selectAll().where { Families.id eq familyId }.single()
            val active = countActive(familyId)
            if (family[Families.adminUserId] == userId && active > 1L) {
                throw ApiException(HttpStatusCode.Conflict, "admin_must_transfer", "请先转让管理员")
            }
            val now = now()
            FamilyMembers.update({
                (FamilyMembers.familyId eq familyId) and (FamilyMembers.userId eq userId) and FamilyMembers.leftAt.isNull()
            }) {
                it[leftAt] = now
                it[leftReason] = "left"
            }
            if (active == 1L) {
                Families.update({ Families.id eq familyId }) {
                    it[emptySince] = now
                    it[inviteCode] = null
                    it[inviteExpiresAt] = null
                    it[updatedAt] = now
                }
            }
            familyId
        }
        signals(FamilySignal.Removed(userId, familyId))
        signals(FamilySignal.Changed(familyId))
    }

    fun rename(userId: Uuid, rawName: String): FamilyView = transaction {
        val member = activeMembership(userId)
            ?: throw ApiException(HttpStatusCode.Forbidden, "not_in_family", "不在家庭中")
        val familyId = member[FamilyMembers.familyId]
        val family = Families.selectAll().where { Families.id eq familyId }.single()
        if (family[Families.adminUserId] != userId) {
            throw ApiException(HttpStatusCode.Forbidden, "admin_only", "只有管理员可以修改家庭名")
        }
        val name = rawName.trim()
        FieldLimits.familyNameError(name)?.let { throw ApiException(HttpStatusCode.BadRequest, "invalid_name", it) }
        val now = now()
        Families.update({ Families.id eq familyId }) {
            it[Families.name] = name
            it[updatedAt] = now
            it[rev] = family[Families.rev] + 1
        }
        load(familyId, userId)
    }.also { signals(FamilySignal.Changed(Uuid.parse(it.id))) }

    fun resetInvite(userId: Uuid): FamilyView = transaction {
        val familyId = requireAdmin(userId)
        val now = now()
        Families.update({ Families.id eq familyId }) {
            it[inviteCode] = freshCode()
            it[inviteExpiresAt] = now.plus(INVITE_TTL)
            it[updatedAt] = now
        }
        load(familyId, userId)
    }.also { signals(FamilySignal.Changed(Uuid.parse(it.id))) }

    fun transfer(userId: Uuid, target: Uuid): FamilyView = transaction {
        val familyId = requireAdmin(userId)
        if (target == userId) throw ApiException(HttpStatusCode.BadRequest, "invalid_target", "不能转让给自己")
        val row = activeMember(familyId, target)
            ?: throw ApiException(HttpStatusCode.NotFound, "not_found", "未找到")
        FamilyAccess.decide(familyId, row[FamilyMembers.familyId]).orThrow()
        val now = now()
        FamilyMembers.update({
            (FamilyMembers.familyId eq familyId) and (FamilyMembers.userId eq userId) and FamilyMembers.leftAt.isNull()
        }) { it[role] = "member" }
        FamilyMembers.update({
            (FamilyMembers.familyId eq familyId) and (FamilyMembers.userId eq target) and FamilyMembers.leftAt.isNull()
        }) { it[role] = "admin" }
        Families.update({ Families.id eq familyId }) {
            it[adminUserId] = target
            it[updatedAt] = now
        }
        load(familyId, userId)
    }.also { signals(FamilySignal.Changed(Uuid.parse(it.id))) }

    fun remove(userId: Uuid, target: Uuid) {
        val familyId = transaction {
            val familyId = requireAdmin(userId)
            if (target == userId) throw ApiException(HttpStatusCode.BadRequest, "invalid_target", "不能移除自己")
            val row = activeMember(familyId, target)
                ?: throw ApiException(HttpStatusCode.NotFound, "not_found", "未找到")
            FamilyAccess.decide(familyId, row[FamilyMembers.familyId]).orThrow()
            val now = now()
            FamilyMembers.update({
                (FamilyMembers.familyId eq familyId) and (FamilyMembers.userId eq target) and FamilyMembers.leftAt.isNull()
            }) {
                it[leftAt] = now
                it[leftReason] = "removed"
            }
            familyId
        }
        signals(FamilySignal.Removed(target, familyId))
        signals(FamilySignal.Changed(familyId))
    }

    fun current(userId: Uuid): FamilyView = transaction {
        val member = activeMembership(userId)
            ?: throw ApiException(HttpStatusCode.Forbidden, "not_in_family", "不在家庭中")
        load(member[FamilyMembers.familyId], userId)
    }

    fun currentFamilyId(userId: Uuid): Uuid? = transaction { activeMembership(userId)?.get(FamilyMembers.familyId) }

    private fun requireAdmin(userId: Uuid): Uuid {
        val member = activeMembership(userId)
            ?: throw ApiException(HttpStatusCode.Forbidden, "not_in_family", "不在家庭中")
        val familyId = member[FamilyMembers.familyId]
        val family = Families.selectAll().where { Families.id eq familyId }.single()
        if (family[Families.adminUserId] != userId) {
            throw ApiException(HttpStatusCode.Forbidden, "admin_only", "只有管理员可以执行该操作")
        }
        return familyId
    }

    private fun load(familyId: Uuid, viewer: Uuid): FamilyView {
        val family = Families.selectAll().where { Families.id eq familyId }.single()
        val members = FamilyMembers.selectAll().where {
            (FamilyMembers.familyId eq familyId) and FamilyMembers.leftAt.isNull()
        }.orderBy(FamilyMembers.joinedAt to SortOrder.ASC).map { row ->
            val uid = row[FamilyMembers.userId]
            val name = Users.selectAll().where { Users.id eq uid }.singleOrNull()
                ?.get(Users.displayName)
                ?.trim()
                ?.ifEmpty { null }
                ?: "用户"
            MemberView(uid.toString(), name, row[FamilyMembers.role], row[FamilyMembers.joinedAt].toString())
        }
        val role = members.first { it.userId == viewer.toString() }.role
        return FamilyView(
            id = family[Families.id].toString(),
            name = family[Families.name],
            adminUserId = family[Families.adminUserId]?.toString(),
            inviteCode = family[Families.inviteCode]?.trim(),
            inviteExpiresAt = family[Families.inviteExpiresAt]?.toString(),
            role = role,
            members = members,
        )
    }

    private fun activeMembership(userId: Uuid) =
        FamilyMembers.selectAll().where {
            (FamilyMembers.userId eq userId) and FamilyMembers.leftAt.isNull()
        }.singleOrNull()

    private fun activeMember(familyId: Uuid, userId: Uuid) =
        FamilyMembers.selectAll().where {
            (FamilyMembers.familyId eq familyId) and (FamilyMembers.userId eq userId) and FamilyMembers.leftAt.isNull()
        }.singleOrNull()

    private fun countActive(familyId: Uuid): Long =
        FamilyMembers.selectAll().where {
            (FamilyMembers.familyId eq familyId) and FamilyMembers.leftAt.isNull()
        }.count()

    private fun freshCode(): String {
        repeat(8) {
            val code = InviteCodes.generate(random)
            val taken = Families.selectAll().where { Families.inviteCode eq code }.count() > 0L
            if (!taken) return code
        }
        error("invite code space exhausted")
    }

    private fun now(): OffsetDateTime = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

    companion object {
        val INVITE_TTL: Duration = Duration.ofDays(7)
        val EMPTY_KEEP: Duration = Duration.ofDays(30)
    }
}

private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }

object InviteCodes {
    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun generate(random: SecureRandom): String = buildString(6) {
        repeat(6) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }

    fun normalize(raw: String): String? {
        val s = raw.trim().uppercase().replace(" ", "")
        if (s.length != 6 || s.any { it !in ALPHABET }) return null
        return s
    }
}

@Serializable
data class MemberView(val userId: String, val displayName: String, val role: String, val joinedAt: String)

@Serializable
data class FamilyView(
    val id: String,
    val name: String,
    val adminUserId: String?,
    val inviteCode: String?,
    val inviteExpiresAt: String?,
    val role: String,
    val members: List<MemberView>,
)
