package com.pantrykeeper.server

import com.pantrykeeper.server.config.AdjustableClock
import com.pantrykeeper.server.config.AppConfig
import com.pantrykeeper.server.config.AuthMode
import com.pantrykeeper.server.config.DotEnv
import com.pantrykeeper.server.db.DatabaseFactory
import com.pantrykeeper.server.family.PurgeService
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory
import java.time.Clock
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val log = LoggerFactory.getLogger("pantry")
    val config = try {
        AppConfig.load(DotEnv.loadMerged())
    } catch (e: IllegalStateException) {
        log.error(e.message)
        exitProcess(1)
    }
    if (config.authMode == AuthMode.TEST) {
        log.warn("AUTH_MODE=test is enabled")
    }
    val clock: Clock = AdjustableClock(Clock.systemUTC()).also { it.adjustable = config.testClockEnabled }
    DatabaseFactory.init(config)
    if (args.firstOrNull() == "purge") {
        val n = PurgeService(clock).purge()
        println("purged=$n")
        return
    }
    embeddedServer(Netty, host = config.listenHost, port = config.listenPort) {
        module(config, clock)
    }.start(wait = true)
}
