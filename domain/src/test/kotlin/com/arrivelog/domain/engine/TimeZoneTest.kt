package com.arrivelog.domain.engine

import com.arrivelog.domain.model.DetectionTuning
import com.arrivelog.domain.model.ScheduleProfile
import com.arrivelog.domain.model.ScheduleSettings
import com.arrivelog.domain.schedule.DayTimeline
import com.arrivelog.domain.testing.EngineHarness
import com.arrivelog.domain.testing.MON
import com.arrivelog.domain.testing.TUE
import com.arrivelog.domain.testing.at
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class TimeZoneTest {

    private val london = ZoneId.of("Europe/London")
    private val kolkata = ZoneId.of("Asia/Kolkata")

    @Test
    fun `day boundaries follow the device zone`() {
        val h = EngineHarness()
        // 20:30 UTC on Monday is 00:30 on Tuesday in Dubai.
        assertThat(h.engine.localDateOf(Instant.parse("2026-10-26T20:30:00Z"))).isEqualTo(TUE)
    }

    @Test
    fun `after a manual zone change the window is evaluated in the new zone`() {
        val h = EngineHarness(zone = kolkata)
        // 08:00 IST is inside 06:30–11:00 IST, even though it is 06:30 in Dubai.
        val d = h.wifi(MON.at(8, 0, kolkata))
        assertThat(d.arrival?.at).isEqualTo(MON.at(8, 0, kolkata))
        // 06:00 IST is 04:30 Dubai: before the window in either zone.
        assertThat(h.wifi(TUE.at(6, 0, kolkata)).outcomes.single().reason).isEqualTo(DecisionReason.BEFORE_WINDOW)
    }

    @Test
    fun `window start inside a DST gap shifts forward by the gap`() {
        // UK clocks jump 01:00 GMT -> 02:00 BST on Sun 29 Mar 2026; 01:30 local does not exist.
        val date = LocalDate.of(2026, 3, 29)
        val profile = ScheduleProfile("N", "Night", LocalTime.of(1, 30), LocalTime.of(4, 0), LocalTime.of(2, 0), LocalTime.of(10, 0))
        val timeline = DayTimeline.of(date, profile, london, DetectionTuning())
        assertThat(timeline.windowStart).isEqualTo(Instant.parse("2026-03-29T01:30:00Z")) // 02:30 BST

        val h = EngineHarness(
            zone = london,
            schedule = ScheduleSettings(profiles = listOf(profile), assignments = mapOf(DayOfWeek.SUNDAY to "N")),
        )
        assertThat(h.wifi(Instant.parse("2026-03-29T00:50:00Z")).outcomes.single().reason).isEqualTo(DecisionReason.BEFORE_WINDOW)
        assertThat(h.wifi(Instant.parse("2026-03-29T01:40:00Z")).arrival).isNotNull()
    }

    @Test
    fun `ambiguous local time at DST end takes the earlier offset`() {
        // UK clocks fall back 02:00 BST -> 01:00 GMT on Sun 25 Oct 2026; 01:30 happens twice.
        val date = LocalDate.of(2026, 10, 25)
        val profile = ScheduleProfile("N", "Night", LocalTime.of(1, 30), LocalTime.of(4, 0), LocalTime.of(2, 0), LocalTime.of(10, 0))
        val timeline = DayTimeline.of(date, profile, london, DetectionTuning())
        assertThat(timeline.windowStart).isEqualTo(Instant.parse("2026-10-25T00:30:00Z")) // 01:30 BST
        // The 4-hour window really is 4.5 hours long that night; nothing breaks.
        assertThat(timeline.windowEnd).isEqualTo(Instant.parse("2026-10-25T04:00:00Z"))
    }
}
