package com.arrivelog.domain.engine

import com.arrivelog.domain.model.DayStatus
import com.arrivelog.domain.model.ScheduleSettings
import com.arrivelog.domain.model.SignalSource
import com.arrivelog.domain.model.WorkDay
import com.arrivelog.domain.testing.EngineHarness
import com.arrivelog.domain.testing.FRI
import com.arrivelog.domain.testing.MON
import com.arrivelog.domain.testing.SAT
import com.arrivelog.domain.testing.SUN
import com.arrivelog.domain.testing.THU
import com.arrivelog.domain.testing.TUE
import com.arrivelog.domain.testing.at
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.DayOfWeek

/** Weekend, holidays, entry window and the Friday profile. */
class ScheduleGatingTest {

    private val h = EngineHarness()

    @Test
    fun `saturday is ignored entirely`() {
        val d = h.wifi(SAT.at(8, 0))
        assertThat(d.outcomes).containsExactly(Outcome.Ignored(DecisionReason.NON_WORKING_DAY))
        assertThat(d.accepted).isFalse()
        assertThat(h.day(SAT)).isNull()
    }

    @Test
    fun `sunday geofence arrival is ignored and leaves no state behind`() {
        h.arriveByGeofence(SUN.at(8, 0))
        assertThat(h.day(SUN)).isNull()
        assertThat(h.states[SUN]?.geofencePresence).isNull()
    }

    @Test
    fun `holiday and leave dates are skipped`() {
        h.schedule = ScheduleSettings(daysOff = mapOf(TUE to DayStatus.HOLIDAY, THU to DayStatus.LEAVE))
        assertThat(h.wifi(TUE.at(8, 0)).outcomes.single().reason).isEqualTo(DecisionReason.NON_WORKING_DAY)
        assertThat(h.wifi(THU.at(8, 0)).outcomes.single().reason).isEqualTo(DecisionReason.NON_WORKING_DAY)
        assertThat(h.wifi(MON.at(8, 0)).arrival).isNotNull()
    }

    @Test
    fun `a weekday switched OFF behaves like the weekend`() {
        h.schedule = ScheduleSettings(assignments = ScheduleSettings.DEFAULT_ASSIGNMENTS - DayOfWeek.MONDAY)
        assertThat(h.wifi(MON.at(8, 0)).outcomes.single().reason).isEqualTo(DecisionReason.NON_WORKING_DAY)
    }

    @Test
    fun `day marked WFH ignores auto signals`() {
        h.days[MON] = WorkDay(MON, status = DayStatus.WFH)
        val d = h.wifi(MON.at(8, 0))
        assertThat(d.outcomes.single().reason).isEqualTo(DecisionReason.DAY_MARKED_NOT_WORKING)
        assertThat(h.day(MON)?.arrivalAt).isNull()
    }

    @Test
    fun `signal before detection is armed is logged but never creates an arrival`() {
        val d = h.wifi(MON.at(6, 14))
        assertThat(d.outcomes.single()).isEqualTo(Outcome.Ignored(DecisionReason.BEFORE_WINDOW))
        assertThat(h.day(MON)).isNull()
    }

    @Test
    fun `signal after the entry window is ignored`() {
        val d = h.wifi(MON.at(11, 5))
        assertThat(d.outcomes.single()).isEqualTo(Outcome.Ignored(DecisionReason.AFTER_WINDOW))
        assertThat(h.day(MON)).isNull()
    }

    @Test
    fun `arm time is inclusive and window end is exclusive`() {
        assertThat(h.wifi(MON.at(6, 15)).arrival?.at).isEqualTo(MON.at(6, 15))
        assertThat(h.wifi(TUE.at(11, 0)).outcomes.single().reason).isEqualTo(DecisionReason.AFTER_WINDOW)
    }

    @Test
    fun `in-window ENTER whose DWELL is delivered after the window closes still counts`() {
        // ENTER at 10:58 (inside), DWELL processed at 11:03: the true arrival time is inside the window.
        h.enter(MON.at(10, 58))
        val d = h.dwell(MON.at(11, 0), receivedAt = MON.at(11, 3))
        assertThat(d.arrival?.at).isEqualTo(MON.at(10, 58))
        assertThat(d.arrival?.source).isEqualTo(SignalSource.GEOFENCE)
    }

    @Test
    fun `early arrival before the window opens is logged at its true time`() {
        val d = h.arriveByGeofence(MON.at(6, 22))
        assertThat(d.arrival?.at).isEqualTo(MON.at(6, 22))
        assertThat(d.arrival?.estimated).isFalse()
    }

    @Test
    fun `already inside when detection starts is logged at arm time and flagged estimated`() {
        // Initial-trigger ENTER at registration (06:15), DWELL two minutes later.
        val d = h.arriveByGeofence(MON.at(6, 15))
        assertThat(d.arrival?.at).isEqualTo(MON.at(6, 15))
        assertThat(d.arrival?.estimated).isTrue()
    }

    @Test
    fun `friday uses profile B and its window closes at 10_00`() {
        assertThat(h.wifi(FRI.at(10, 5)).outcomes.single().reason).isEqualTo(DecisionReason.AFTER_WINDOW)
        assertThat(h.wifi(THU.at(10, 5)).arrival?.at).isEqualTo(THU.at(10, 5))
    }

    @Test
    fun `friday early arrival inside profile B window is recorded`() {
        val d = h.wifi(FRI.at(7, 20))
        assertThat(d.arrival?.at).isEqualTo(FRI.at(7, 20))
        assertThat(h.engine.timelineFor(FRI, h.schedule)?.expectedStart).isEqualTo(FRI.at(7, 30))
    }
}
