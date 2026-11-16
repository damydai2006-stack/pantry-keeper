package com.pantrykeeper.server.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.time
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

object Users : Table("users") {
    val id = uuid("id")
    val phoneEnc = binary("phone_enc")
    val phoneHmac = binary("phone_hmac")
    val displayName = text("display_name")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object AuthCodes : Table("auth_codes") {
    val phoneHmac = binary("phone_hmac")
    val codeHash = text("code_hash")
    val expiresAt = timestampWithTimeZone("expires_at")
    val attempts = integer("attempts")
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(phoneHmac)
}

object Sessions : Table("sessions") {
    val id = uuid("id")
    val userId = uuid("user_id")
    val tokenHash = binary("token_hash")
    val clientKind = text("client_kind")
    val deviceName = text("device_name").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val lastUsedAt = timestampWithTimeZone("last_used_at")
    val expiresAt = timestampWithTimeZone("expires_at")
    override val primaryKey = PrimaryKey(id)
}

object UserSettings : Table("user_settings") {
    val userId = uuid("user_id")
    val notifyTime = time("notify_time")
    val updatedAt = timestampWithTimeZone("updated_at")
    val rev = long("rev")
    override val primaryKey = PrimaryKey(userId)
}

object Families : Table("families") {
    val id = uuid("id")
    val name = text("name")
    val adminUserId = uuid("admin_user_id").nullable()
    val inviteCode = text("invite_code").nullable()
    val inviteExpiresAt = timestampWithTimeZone("invite_expires_at").nullable()
    val emptySince = timestampWithTimeZone("empty_since").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    val rev = long("rev")
    override val primaryKey = PrimaryKey(id)
}

object FamilyMembers : Table("family_members") {
    val familyId = uuid("family_id")
    val userId = uuid("user_id")
    val role = text("role")
    val joinedAt = timestampWithTimeZone("joined_at")
    val leftAt = timestampWithTimeZone("left_at").nullable()
    val leftReason = text("left_reason").nullable()
}
