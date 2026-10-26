package com.arrivelog.domain.health

import com.arrivelog.domain.model.RawEvent
import com.arrivelog.domain.model.RawEventType
import java.time.Duration
import java.time.Instant

/** Battery audit numbers (spec 5b "Measure it"), rebuilt from the SYSTEM rows of the event log. */
data class ListenerUptime(val geofence: Duration, val wifiCallback: Duration)

object ListenerUptimeCalculator {
    /**
     * How long geofences and the Wi-Fi callback were registered within [from]..[to]. Pass events
     * from a little before [from] (a day or two) so a registration made the evening before counts.
     */
    fun compute(events: List<RawEvent>, from: Instant, to: Instant): ListenerUptime {
        val system = events.filter { it.type == RawEventType.SYSTEM }.sortedBy { it.at }
        return ListenerUptime(
            geofence = uptime(system, from, to, on = { it.startsWith("GEOFENCE_ARMED") }, off = { it.startsWith("GEOFENCE_REMOVED") || it.startsWith("GEOFENCE_FAILED") }),
            wifiCallback = uptime(system, from, to, on = { it == "WIFI_CALLBACK_ARMED" }, off = { it == "WIFI_CALLBACK_REMOVED" || it.startsWith("WIFI_CALLBACK_FAILED") }),
        )
    }

    private fun uptime(events: List<RawEvent>, from: Instant, to: Instant, on: (String) -> Boolean, off: (String) -> Boolean): Duration {
        var total = Duration.ZERO
        var armedSince: Instant? = null
        events.forEach { e ->
            if (e.at.isAfter(to)) return@forEach
            when {
                // Re-arming (e.g. ARRIVAL → PRESENCE) keeps the listener up: it is not a new interval.
                on(e.reason) -> if (armedSince == null) armedSince = e.at
                off(e.reason) -> armedSince?.let { start ->
                    total += overlap(start, e.at, from, to)
                    armedSince = null
                }
            }
        }
        armedSince?.let { total += overlap(it, to, from, to) }
        return total
    }

    private fun overlap(start: Instant, end: Instant, from: Instant, to: Instant): Duration {
        val s = maxOf(start, from)
        val e = minOf(end, to)
        return if (e.isAfter(s)) Duration.between(s, e) else Duration.ZERO
    }
}
