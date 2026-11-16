package com.pantrykeeper.server.family

import com.pantrykeeper.server.db.Families
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

class PurgeService(private val clock: Clock) {
    fun purge(): Int = transaction {
        val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        val cutoff = now.minus(FamilyService.EMPTY_KEEP)
        val ids = Families.selectAll().where {
            Families.emptySince.isNotNull() and (Families.emptySince lessEq cutoff)
        }.map { it[Families.id] }
        for (id in ids) {
            val key = id.toString()
            exec("delete from photos where family_id = '$key'")
            exec("delete from item_events where family_id = '$key'")
            exec("delete from item_tombstones where family_id = '$key'")
            exec("delete from items where family_id = '$key'")
            exec("delete from batch_ops where family_id = '$key'")
            exec("delete from barcode_cache where family_id = '$key'")
            exec("delete from family_members where family_id = '$key'")
            exec("delete from families where id = '$key'")
        }
        ids.size
    }
}
