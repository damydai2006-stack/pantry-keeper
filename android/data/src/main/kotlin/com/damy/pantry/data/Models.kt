package com.damy.pantry.data

import kotlinx.serialization.Serializable

@Serializable
data class CachedItem(
    val id: String,
    val name: String,
    val category: String,
    val quantity: Int,
    val location: String? = null,
    val note: String? = null,
    val expiryDate: String,
    val productionDate: String? = null,
    val shelfLifeValue: Int? = null,
    val shelfLifeUnit: String? = null,
    val expiryBasis: String = "manual_date",
    val reminderTiers: List<String> = emptyList(),
    val status: String,
    val rev: Long,
    val photoId: String? = null,
)

@Serializable
data class Member(
    val userId: String,
    val displayName: String = "",
    val role: String,
    val joinedAt: String,
)

@Serializable
data class Family(
    val id: String,
    val name: String,
    val adminUserId: String? = null,
    val inviteCode: String? = null,
    val inviteExpiresAt: String? = null,
    val role: String,
    val members: List<Member> = emptyList(),
)

@Serializable
data class Tombstone(val itemId: String, val rev: Long)

@Serializable
data class SyncPage(
    val reset: Boolean = false,
    val rev: Long = 0,
    val items: List<CachedItem> = emptyList(),
    val tombstones: List<Tombstone> = emptyList(),
    val family: Family? = null,
    val notifyTime: String? = null,
)

@Serializable
data class LoginResult(val userId: String, val displayName: String, val token: String)

@Serializable
data class Me(val userId: String, val displayName: String, val notifyTime: String, val familyId: String? = null)

@Serializable
data class BatchOutcome(val undoToken: String? = null, val rev: Long = 0)

@Serializable
data class UndoOutcome(val skipped: Int = 0, val restored: Int = 0, val message: String? = null)

data class ItemDraft(
    val name: String,
    val category: String,
    val quantity: Int,
    val location: String?,
    val note: String?,
    val expiryDate: String?,
    val productionDate: String?,
    val shelfLifeValue: Int?,
    val shelfLifeUnit: String?,
    val expiryBasis: String?,
    val reminderTiers: List<String>,
)

class PantryCallException(val status: Int, val code: String, message: String) : Exception(message)

class OfflineException : Exception("恢复网络后再修改")

fun userFacingMessage(error: Throwable): String = when (error) {
    is OfflineException -> error.message ?: "恢复网络后再修改"
    is PantryCallException -> error.message ?: "操作失败"
    is java.io.IOException -> "当前无网络，请稍后重试"
    else -> "操作失败，请稍后重试"
}

sealed interface AppEvent {
    data object Revoked : AppEvent
    data object Removed : AppEvent
    data class Toast(val text: String) : AppEvent
}
