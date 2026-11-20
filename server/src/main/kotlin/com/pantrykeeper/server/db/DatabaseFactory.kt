package com.pantrykeeper.server.db

import com.pantrykeeper.server.config.AppConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database

object DatabaseFactory {
    private val lock = Any()

    @Volatile
    var ready: Boolean = false
        private set

    lateinit var dataSource: HikariDataSource
        private set

    fun init(config: AppConfig) {
        if (ready) return
        synchronized(lock) {
            if (ready) return
            val hikari = HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = config.dbUrl
                    username = config.dbUser
                    password = config.dbPassword
                    maximumPoolSize = config.poolSize
                    poolName = "pantry"
                },
            )
            Flyway.configure()
                .dataSource(hikari)
                .locations("classpath:db/migration")
                .load()
                .migrate()
            Database.connect(hikari)
            dataSource = hikari
            ready = true
        }
    }

    fun close() {
        synchronized(lock) {
            if (::dataSource.isInitialized && !dataSource.isClosed) {
                dataSource.close()
            }
            ready = false
        }
    }
}
