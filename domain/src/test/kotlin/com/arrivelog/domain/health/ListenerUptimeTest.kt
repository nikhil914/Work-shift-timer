package com.arrivelog.domain.health

import com.arrivelog.domain.model.RawEvent
import com.arrivelog.domain.model.RawEventType
import com.arrivelog.domain.testing.MON
import com.arrivelog.domain.testing.at
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration

class ListenerUptimeTest {

    private fun sys(h: Int, m: Int, reason: String, date: java.time.LocalDate = MON) =
        RawEvent(at = date.at(h, m), receivedAt = date.at(h, m), type = RawEventType.SYSTEM, accepted = true, reason = reason)

    @Test
    fun `counts armed intervals inside the day, re-arming does not restart them`() {
        val events = listOf(
            sys(6, 15, "GEOFENCE_ARMED:ARRIVAL:AWAITING_ARRIVAL"),
            sys(6, 15, "WIFI_CALLBACK_ARMED"),
            sys(7, 55, "GEOFENCE_ARMED:PRESENCE:TRACKING_EXIT"),
            sys(7, 55, "WIFI_CALLBACK_REMOVED"),
            sys(19, 0, "GEOFENCE_REMOVED:PAST_DISARM"),
            sys(19, 0, "WAKE:ALARM"),
        )
        val u = ListenerUptimeCalculator.compute(events, MON.at(0, 0), MON.at(23, 0))
        assertThat(u.geofence).isEqualTo(Duration.ofMinutes(12 * 60 + 45))
        assertThat(u.wifiCallback).isEqualTo(Duration.ofMinutes(100))
    }

    @Test
    fun `a registration still up at the end or from the day before is clipped to the range`() {
        val events = listOf(
            sys(22, 0, "GEOFENCE_ARMED:PRESENCE:TRACKING_EXIT", MON.minusDays(1)),
            sys(1, 0, "GEOFENCE_FAILED:boom"),
            sys(9, 0, "GEOFENCE_ARMED:ARRIVAL:AWAITING_ARRIVAL"),
        )
        val u = ListenerUptimeCalculator.compute(events, MON.at(0, 0), MON.at(10, 0))
        assertThat(u.geofence).isEqualTo(Duration.ofHours(2))
        assertThat(u.wifiCallback).isEqualTo(Duration.ZERO)
    }
}
