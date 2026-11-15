package com.pantrykeeper.server.auth

import com.pantrykeeper.server.config.AppConfig
import com.pantrykeeper.server.config.AuthMode
import com.pantrykeeper.server.db.AuthCodes
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
import java.time.Clock
import java.time.Duration
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.ArrayDeque
import kotlin.uuid.Uuid

class AuthService(
    private val config: AppConfig,
    private val clock: Clock,
    private val crypto: PhoneCrypto,
    private val sessions: SessionService,
) {
    private val codeLimiter = SlidingLimiter(clock, maxHits = 1, window = Duration.ofMinutes(1))

    fun requestCode(rawPhone: String) {
        if (config.authMode != AuthMode.TEST) {
            throw ApiException(HttpStatusCode.Forbidden, "auth_disabled", "登录未开放")
        }
        val phone = Phones.normalize(rawPhone) ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_phone", "手机号不正确")
        codeLimiter.hit(phone)
        if (phone !in config.testPhones) return
        val now = OffsetDateTime.now(clock)
        val hash = crypto.hmacHex(config.testCode)
        val hmac = crypto.hmac(phone)
        transaction {
            val existing = AuthCodes.selectAll().where { AuthCodes.phoneHmac eq hmac }.singleOrNull()
            if (existing == null) {
                AuthCodes.insert {
                    it[phoneHmac] = hmac
                    it[codeHash] = hash
                    it[expiresAt] = now.plusMinutes(10)
                    it[attempts] = 0
                    it[createdAt] = now
                }
            } else {
                AuthCodes.update({ AuthCodes.phoneHmac eq hmac }) {
                    it[codeHash] = hash
                    it[expiresAt] = now.plusMinutes(10)
                    it[attempts] = 0
                    it[createdAt] = now
                }
            }
        }
    }

    fun login(rawPhone: String, code: String, clientKind: String, deviceName: String?): LoginResult {
        if (config.authMode != AuthMode.TEST) {
            throw ApiException(HttpStatusCode.Forbidden, "auth_disabled", "登录未开放")
        }
        val phone = Phones.normalize(rawPhone) ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_phone", "手机号不正确")
        if (clientKind != "app" && clientKind != "web") {
            throw ApiException(HttpStatusCode.BadRequest, "invalid_client", "client_kind 不正确")
        }
        if (deviceName != null && deviceName.length > 80) {
            throw ApiException(HttpStatusCode.BadRequest, "invalid_device", "设备名过长")
        }
        if (phone !in config.testPhones) {
            throw ApiException(HttpStatusCode.Unauthorized, "login_failed", "登录失败")
        }
        val now = OffsetDateTime.now(clock)
        val hmac = crypto.hmac(phone)
        val outcome = transaction {
            val row = AuthCodes.selectAll().where { AuthCodes.phoneHmac eq hmac }.singleOrNull()
                ?: return@transaction LoginOutcome.Failed("login_failed", "登录失败")
            if (!now.isBefore(row[AuthCodes.expiresAt])) {
                return@transaction LoginOutcome.Failed("code_expired", "验证码已过期")
            }
            val attempts = row[AuthCodes.attempts]
            if (attempts >= 5) {
                return@transaction LoginOutcome.Failed("code_invalidated", "验证码已作废")
            }
            val presented = crypto.hmacHex(code)
            if (!crypto.constantEquals(presented, row[AuthCodes.codeHash])) {
                val next = attempts + 1
                AuthCodes.update({ AuthCodes.phoneHmac eq hmac }) { it[AuthCodes.attempts] = next }
                return@transaction if (next >= 5) {
                    LoginOutcome.Failed("code_invalidated", "验证码已作废")
                } else {
                    LoginOutcome.Failed("code_incorrect", "验证码不正确")
                }
            }
            AuthCodes.deleteWhere { phoneHmac eq hmac }
            LoginOutcome.Ok(upsertUser(hmac, crypto.encrypt(phone), now))
        }
        val userId = when (outcome) {
            is LoginOutcome.Failed -> throw ApiException(HttpStatusCode.Unauthorized, outcome.code, outcome.message)
            is LoginOutcome.Ok -> outcome.userId
        }
        val session = sessions.issue(userId, clientKind, deviceName)
        return LoginResult(userId, "用户", clientKind, session.token)
    }

    private fun upsertUser(hmac: ByteArray, enc: ByteArray, now: OffsetDateTime): Uuid {
        val existing = Users.selectAll().where { Users.phoneHmac eq hmac }.singleOrNull()
        if (existing != null) return existing[Users.id]
        val id = Uuid.random()
        Users.insert {
            it[Users.id] = id
            it[phoneEnc] = enc
            it[phoneHmac] = hmac
            it[displayName] = "用户"
            it[createdAt] = now
            it[updatedAt] = now
        }
        UserSettings.insert {
            it[userId] = id
            it[notifyTime] = LocalTime.of(9, 0)
            it[updatedAt] = now
            it[rev] = 0
        }
        return id
    }
}

data class LoginResult(val userId: Uuid, val displayName: String, val clientKind: String, val token: String)

private sealed interface LoginOutcome {
    data class Ok(val userId: Uuid) : LoginOutcome
    data class Failed(val code: String, val message: String) : LoginOutcome
}

class SlidingLimiter(private val clock: Clock, private val maxHits: Int, private val window: Duration) {
    private val hits = HashMap<String, ArrayDeque<java.time.Instant>>()

    fun hit(key: String) {
        synchronized(hits) {
            val now = clock.instant()
            val q = hits.getOrPut(key) { ArrayDeque() }
            val iter = q.iterator()
            while (iter.hasNext()) {
                val hit = iter.next()
                if (hit.isAfter(now) || Duration.between(hit, now) > window) iter.remove()
            }
            if (q.size >= maxHits) throw ApiException(HttpStatusCode.TooManyRequests, "rate_limited", "操作太频繁")
            q.addLast(now)
        }
    }
}
