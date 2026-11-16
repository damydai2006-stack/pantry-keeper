package com.pantrykeeper.server.items

import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.sql.Connection
import java.sql.ResultSet

fun jdbc(): Connection = TransactionManager.current().connection.connection as Connection

fun <T> queryOne(sql: String, bind: (java.sql.PreparedStatement) -> Unit, read: (ResultSet) -> T): T? {
    jdbc().prepareStatement(sql).use { ps ->
        bind(ps)
        ps.executeQuery().use { rs ->
            return if (rs.next()) read(rs) else null
        }
    }
}

fun update(sql: String, bind: (java.sql.PreparedStatement) -> Unit): Int {
    jdbc().prepareStatement(sql).use { ps ->
        bind(ps)
        return ps.executeUpdate()
    }
}
