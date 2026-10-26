package com.arrivelog.domain.time

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * System clock that re-reads the default zone on every call. Clock.systemDefaultZone() captures the
 * zone once, so a singleton built from it would keep using the old zone after the user changes it.
 * Android resets the process default TimeZone on ACTION_TIMEZONE_CHANGED, so this tracks it.
 */
class DeviceZoneClock : Clock() {
    override fun getZone(): ZoneId = ZoneId.systemDefault()

    override fun withZone(zone: ZoneId): Clock = system(zone)

    override fun instant(): Instant = Instant.now()

    override fun millis(): Long = System.currentTimeMillis()
}
