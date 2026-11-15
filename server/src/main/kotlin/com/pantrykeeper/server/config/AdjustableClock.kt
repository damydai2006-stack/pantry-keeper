package com.pantrykeeper.server.config

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong

/** Base clock plus an offset. The offset can be changed only when [adjustable] is true. */
class AdjustableClock(private val base: Clock) : Clock() {
    var adjustable: Boolean = false
    private val offsetSeconds = AtomicLong(0)

    fun setOffsetSeconds(seconds: Long) {
        check(adjustable) { "test clock is disabled" }
        offsetSeconds.set(seconds)
    }

    fun offsetSeconds(): Long = offsetSeconds.get()

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = base.instant().plusSeconds(offsetSeconds.get())
}
