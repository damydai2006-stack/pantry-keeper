package com.pantrykeeper.server.family

import com.pantrykeeper.server.http.ApiException
import io.ktor.http.HttpStatusCode
import kotlin.uuid.Uuid

enum class ResourceDecision { ALLOW, NO_FAMILY, NOT_FOUND }

object FamilyAccess {
    /** Foreign or unknown ids are NOT_FOUND so the response can stay empty of family data. */
    fun decide(callerFamilyId: Uuid?, resourceFamilyId: Uuid): ResourceDecision {
        if (callerFamilyId == null) return ResourceDecision.NO_FAMILY
        if (callerFamilyId != resourceFamilyId) return ResourceDecision.NOT_FOUND
        return ResourceDecision.ALLOW
    }

    fun decideAll(callerFamilyId: Uuid?, resourceFamilyIds: Collection<Uuid>): ResourceDecision {
        if (callerFamilyId == null) return ResourceDecision.NO_FAMILY
        if (resourceFamilyIds.any { it != callerFamilyId }) return ResourceDecision.NOT_FOUND
        return ResourceDecision.ALLOW
    }
}

fun ResourceDecision.orThrow() {
    when (this) {
        ResourceDecision.ALLOW -> Unit
        ResourceDecision.NO_FAMILY -> throw ApiException(HttpStatusCode.Forbidden, "not_in_family", "不在家庭中")
        ResourceDecision.NOT_FOUND -> throw ApiException(HttpStatusCode.NotFound, "not_found", "未找到")
    }
}
