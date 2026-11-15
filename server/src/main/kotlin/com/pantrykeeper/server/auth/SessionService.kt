package com.pantrykeeper.server.auth

import com.pantrykeeper.server.db.Sessions
import com.pantrykeeper.server.db.UserSettings
import com.pantrykeeper.server.db.Users
import com.pantrykeeper.server.http.ApiException
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import kotlin.uuid.Uuid

data class SessionContext(
    val sessionId: Uuid,
    val userId: Uuid,
    val clientKind: String,
    val slid: Boolean,
)

class SessionService(private val clock: Clock) {
    private val random = SecureRandom()
    private val ttl: Duration = Duration.ofDays(30)
    private val touchGap: Duration = Duration.ofMinutes(1)

    fun issue(userId: Uuid, clientKind: String, deviceName: String?): IssuedSession = transaction {
        val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        val tokenBytes = ByteArray(32).also { random.nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
        val id = Uuid.random()
        Sessions.insert {
            it[Sessions.id] = id
            it[Sessions.userId] = userId
            it[tokenHash] = sha256(token.toByteArray(Charsets.UTF_8))
            it[Sessions.clientKind] = clientKind
            it[Sessions.deviceName] = deviceName
            it[createdAt] = now
            it[lastUsedAt] = now
            it[expiresAt] = now.plus(ttl)
        }
        IssuedSession(id, token)
    }

    fun authenticate(token: String): SessionContext? = transaction {
        val hash = sha256(token.toByteArray(Charsets.UTF_8))
        val row = Sessions.selectAll().where { Sessions.tokenHash eq hash }.singleOrNull() ?: return@transaction null
        val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        if (!now.isBefore(row[Sessions.expiresAt])) return@transaction null
        val last = row[Sessions.lastUsedAt]
        val slid = Duration.between(last.toInstant(), now.toInstant()) >= touchGap
        if (slid) {
            Sessions.update({ Sessions.id eq row[Sessions.id] }) {
                it[lastUsedAt] = now
                it[expiresAt] = now.plus(ttl)
            }
        }
        SessionContext(row[Sessions.id], row[Sessions.userId], row[Sessions.clientKind], slid)
    }

    fun logout(sessionId: Uuid) {
        transaction { Sessions.deleteWhere { Sessions.id eq sessionId } }
    }

    /** True only while the session row exists and `expires_at` is still ahead of the clock. */
    fun sessionOpen(sessionId: Uuid): Boolean = transaction {
        val row = Sessions.selectAll().where { Sessions.id eq sessionId }.singleOrNull() ?: return@transaction false
        val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        now.isBefore(row[Sessions.expiresAt])
    }

    fun expiresAt(sessionId: Uuid): OffsetDateTime? = transaction {
        Sessions.selectAll().where { Sessions.id eq sessionId }.singleOrNull()?.get(Sessions.expiresAt)
    }

    fun updateNotifyTime(userId: Uuid, raw: String) {
        val time = runCatching { LocalTime.parse(raw) }.getOrNull()
            ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_time", "时间格式应为 HH:mm")
        val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        transaction {
            val row = UserSettings.selectAll().where { UserSettings.userId eq userId }.singleOrNull()
                ?: throw ApiException(HttpStatusCode.NotFound, "not_found", "未找到")
            UserSettings.update({ UserSettings.userId eq userId }) {
                it[notifyTime] = time
                it[updatedAt] = now
                it[rev] = row[UserSettings.rev] + 1
            }
        }
    }

    fun profile(userId: Uuid): UserProfile = transaction {
        val user = Users.selectAll().where { Users.id eq userId }.single()
        val settings = UserSettings.selectAll().where { UserSettings.userId eq userId }.single()
        UserProfile(user[Users.id], user[Users.displayName], settings[UserSettings.notifyTime].toString().substring(0, 5))
    }
}

data class IssuedSession(val id: Uuid, val token: String)

data class UserProfile(val userId: Uuid, val displayName: String, val notifyTime: String)
