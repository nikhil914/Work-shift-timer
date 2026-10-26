package com.arrivelog.domain.engine

import com.arrivelog.domain.model.DayStatus
import com.arrivelog.domain.model.DetectionSettings
import com.arrivelog.domain.model.DetectionTuning
import com.arrivelog.domain.model.ScheduleSettings
import com.arrivelog.domain.model.SignalSource
import com.arrivelog.domain.model.WorkDay
import com.arrivelog.domain.testing.FRI
import com.arrivelog.domain.testing.MON
import com.arrivelog.domain.testing.MutableClock
import com.arrivelog.domain.testing.OFFICE
import com.arrivelog.domain.testing.SAT
import com.arrivelog.domain.testing.TUE
import com.arrivelog.domain.testing.at
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WakePlannerTest {

    private val clock = MutableClock(MON.at(5, 0))
    private val planner = WakePlanner(clock, DetectionTuning())
    private val schedule = ScheduleSettings()
    private val settings = DetectionSettings()
    private val arrived = WorkDay(MON, arrivalAt = MON.at(8, 0), arrivalSource = SignalSource.WIFI)

    private fun next(
        now: java.time.Instant,
        day: WorkDay? = null,
        missedDone: Boolean = false,
        date: java.time.LocalDate = MON,
        s: DetectionSettings = settings,
        renotified: Boolean = missedDone,
    ) = planner.next(date, schedule, s, listOf(OFFICE), day, missedDone, now, renotified)

    @Test
    fun `working day sequence - arm, missed-entry check, then tomorrow`() {
        assertThat(next(MON.at(5, 0))).isEqualTo(Wake(MON.at(6, 15), WakeReason.ARM))
        assertThat(next(MON.at(6, 15))).isEqualTo(Wake(MON.at(11, 10), WakeReason.MISSED_ENTRY_CHECK))
        assertThat(next(MON.at(11, 10), missedDone = true, renotified = false)).isEqualTo(Wake(MON.at(12, 10), WakeReason.MISSED_ENTRY_RENOTIFY))
        assertThat(next(MON.at(12, 10), missedDone = true, renotified = true)).isEqualTo(Wake(TUE.at(6, 15), WakeReason.NEXT_WORKING_DAY))
    }

    @Test
    fun `after arrival with exit tracking the next wake is disarm`() {
        assertThat(next(MON.at(8, 1), day = arrived)).isEqualTo(Wake(MON.at(20, 0), WakeReason.DISARM))
    }

    @Test
    fun `after arrival without exit tracking nothing more today`() {
        assertThat(next(MON.at(8, 1), day = arrived, s = settings.copy(exitTrackingEnabled = false)))
            .isEqualTo(Wake(TUE.at(6, 15), WakeReason.NEXT_WORKING_DAY))
    }

    @Test
    fun `friday evening skips the weekend to monday`() {
        val nextMonday = MON.plusDays(7)
        assertThat(next(FRI.at(16, 0), date = FRI, missedDone = true)).isEqualTo(Wake(nextMonday.at(6, 15), WakeReason.NEXT_WORKING_DAY))
        assertThat(next(SAT.at(9, 0), date = SAT)).isEqualTo(Wake(nextMonday.at(6, 15), WakeReason.NEXT_WORKING_DAY))
    }

    @Test
    fun `holiday tomorrow is skipped`() {
        val withHoliday = schedule.copy(daysOff = mapOf(TUE to DayStatus.HOLIDAY))
        val w = planner.next(MON, withHoliday, settings, listOf(OFFICE), null, true, MON.at(12, 30), missedEntryRenotified = true)
        assertThat(w).isEqualTo(Wake(TUE.plusDays(1).at(6, 15), WakeReason.NEXT_WORKING_DAY))
    }

    @Test
    fun `day marked WFH has no wakes today`() {
        assertThat(next(MON.at(5, 0), day = WorkDay(MON, status = DayStatus.WFH)))
            .isEqualTo(Wake(TUE.at(6, 15), WakeReason.NEXT_WORKING_DAY))
    }

    @Test
    fun `no workplaces and no working days mean no wakes at all`() {
        assertThat(planner.next(MON, schedule, settings, emptyList(), null, false, MON.at(5, 0))).isNull()
        assertThat(planner.next(MON, ScheduleSettings(assignments = emptyMap()), settings, listOf(OFFICE), null, false, MON.at(5, 0))).isNull()
    }

    @Test
    fun `signature changes only when registration must change`() {
        val tuning = DetectionTuning()
        val p = ListenerPlanner(clock, tuning)
        val ctx = DecisionContext(MON, settings, schedule, listOf(OFFICE), null, com.arrivelog.domain.model.DetectionState(MON))
        val a = GeofenceSignature.of(p.plan(ctx, MON.at(7, 0)), listOf(OFFICE), 120_000)
        val b = GeofenceSignature.of(p.plan(ctx, MON.at(9, 0)), listOf(OFFICE), 120_000)
        val moved = GeofenceSignature.of(p.plan(ctx, MON.at(9, 0)), listOf(OFFICE.copy(radiusMeters = 300)), 120_000)
        assertThat(a).isEqualTo(b)
        assertThat(moved).isNotEqualTo(a)
        assertThat(GeofenceSignature.of(p.plan(ctx, MON.at(5, 0)), listOf(OFFICE), 120_000)).isEqualTo("OFF")
    }
}
