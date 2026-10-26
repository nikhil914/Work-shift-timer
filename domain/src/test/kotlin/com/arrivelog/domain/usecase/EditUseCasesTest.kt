package com.arrivelog.domain.usecase

import com.arrivelog.domain.engine.ArrivalDecisionEngine
import com.arrivelog.domain.engine.GeofenceArming
import com.arrivelog.domain.engine.ListenerPlanner
import com.arrivelog.domain.model.DayStatus
import com.arrivelog.domain.model.DetectionTuning
import com.arrivelog.domain.model.RawEventType
import com.arrivelog.domain.model.Signal
import com.arrivelog.domain.model.SignalSource
import com.arrivelog.domain.model.WorkDay
import com.arrivelog.domain.testing.DUBAI
import com.arrivelog.domain.testing.FakeLog
import com.arrivelog.domain.testing.FakeSettings
import com.arrivelog.domain.testing.FakeStates
import com.arrivelog.domain.testing.FakeWorkDays
import com.arrivelog.domain.testing.FakeWorkplaces
import com.arrivelog.domain.testing.MON
import com.arrivelog.domain.testing.MutableClock
import com.arrivelog.domain.testing.MutexTx
import com.arrivelog.domain.testing.OFFICE
import com.arrivelog.domain.testing.OFFICE_ID
import com.arrivelog.domain.testing.at
import com.arrivelog.domain.testing.officeWifi
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalTime

class EditUseCasesTest {

    private val clock = MutableClock(MON.at(12, 0))
    private val tuning = DetectionTuning()
    private val engine = ArrivalDecisionEngine(clock, tuning)
    private val days = FakeWorkDays()
    private val states = FakeStates()
    private val log = FakeLog()
    private val tx = MutexTx()
    private val loader = DayContextLoader(FakeSettings(), FakeWorkplaces(listOf(OFFICE)), days, states)
    private val process = ProcessSignalUseCase(engine, ListenerPlanner(clock, tuning), loader, days, states, log, tx)
    private val edit = EditDayUseCase(engine, days, states, log, tx)
    private val undo = UndoArrivalUseCase(engine, days, states, log, tx)
    private val mark = MarkDayStatusUseCase(engine, days, log, tx)

    private val autoDay = WorkDay(
        date = MON,
        arrivalAt = MON.at(8, 7),
        arrivalSource = SignalSource.WIFI,
        originalArrivalAt = MON.at(8, 7),
        zoneId = DUBAI,
    )

    private fun t(hhmm: String) = LocalTime.parse(hhmm)

    // DayEditor (pure) --------------------------------------------------------------------------

    @Test
    fun `editing an auto arrival keeps the detected time as the audit trail`() {
        val result = DayEditor.apply(autoDay, MON, DayEdit(t("08:00"), null, DayStatus.WORKING, null), DUBAI, clock.now)
        val day = (result as EditResult.Saved).day
        assertThat(day.arrivalAt).isEqualTo(MON.at(8, 0))
        assertThat(day.arrivalEdited).isTrue()
        assertThat(day.arrivalSource).isEqualTo(SignalSource.WIFI)
        assertThat(day.originalArrivalAt).isEqualTo(MON.at(8, 7))
        assertThat(day.editedManually).isTrue()
    }

    @Test
    fun `a second edit still remembers the first detected time`() {
        val once = (DayEditor.apply(autoDay, MON, DayEdit(t("08:00"), null, DayStatus.WORKING, null), DUBAI, clock.now) as EditResult.Saved).day
        val twice = (DayEditor.apply(once, MON, DayEdit(t("07:55"), null, DayStatus.WORKING, null), DUBAI, clock.now) as EditResult.Saved).day
        assertThat(twice.arrivalAt).isEqualTo(MON.at(7, 55))
        assertThat(twice.originalArrivalAt).isEqualTo(MON.at(8, 7))
    }

    @Test
    fun `a time typed in where nothing was detected is manual, not edited`() {
        val day = (DayEditor.apply(null, MON, DayEdit(t("08:15"), t("16:30"), DayStatus.WORKING, "  traffic  "), DUBAI, clock.now.plusSeconds(6 * 3600)) as EditResult.Saved).day
        assertThat(day.arrivalSource).isEqualTo(SignalSource.MANUAL)
        assertThat(day.arrivalEdited).isFalse()
        assertThat(day.exitSource).isEqualTo(SignalSource.MANUAL)
        assertThat(day.note).isEqualTo("traffic")
        assertThat(day.zoneId).isEqualTo(DUBAI)
    }

    @Test
    fun `exit must come after the arrival and needs one`() {
        assertThat(DayEditor.apply(autoDay, MON, DayEdit(t("08:07"), t("08:00"), DayStatus.WORKING, null), DUBAI, clock.now))
            .isEqualTo(EditResult.Invalid(EditError.EXIT_NOT_AFTER_ARRIVAL))
        assertThat(DayEditor.apply(null, MON, DayEdit(null, t("11:00"), DayStatus.WORKING, null), DUBAI, clock.now))
            .isEqualTo(EditResult.Invalid(EditError.EXIT_WITHOUT_ARRIVAL))
    }

    @Test
    fun `times in the future are refused`() {
        assertThat(DayEditor.apply(autoDay, MON, DayEdit(t("08:07"), t("17:00"), DayStatus.WORKING, null), DUBAI, clock.now))
            .isEqualTo(EditResult.Invalid(EditError.TIME_IN_FUTURE))
    }

    @Test
    fun `holiday and weekend cannot be picked for a single day`() {
        assertThat(DayEditor.apply(null, MON, DayEdit(null, null, DayStatus.HOLIDAY, null), DUBAI, clock.now))
            .isEqualTo(EditResult.Invalid(EditError.STATUS_NOT_SELECTABLE))
    }

    @Test
    fun `saving without changes is a no-op`() {
        assertThat(DayEditor.apply(autoDay, MON, DayEdit(t("08:07"), null, DayStatus.WORKING, null), DUBAI, clock.now))
            .isEqualTo(EditResult.Unchanged)
        assertThat(DayEditor.apply(null, MON, DayEdit(null, null, DayStatus.WORKING, ""), DUBAI, clock.now))
            .isEqualTo(EditResult.Unchanged)
    }

    @Test
    fun `clearing the arrival clears the exit too`() {
        val withExit = autoDay.copy(exitAt = MON.at(11, 0), exitSource = SignalSource.GEOFENCE)
        val result = DayEditor.apply(withExit, MON, DayEdit(null, null, DayStatus.WORKING, null), DUBAI, clock.now) as EditResult.Saved
        assertThat(result.arrivalCleared).isTrue()
        assertThat(result.day.arrivalAt).isNull()
        assertThat(result.day.exitAt).isNull()
    }

    @Test
    fun `editing an auto exit marks it edited so later auto exits cannot overwrite it`() {
        val withExit = autoDay.copy(exitAt = MON.at(11, 0), exitSource = SignalSource.GEOFENCE, originalExitAt = MON.at(11, 0))
        val day = (DayEditor.apply(withExit, MON, DayEdit(t("08:07"), t("11:30"), DayStatus.WORKING, null), DUBAI, clock.now) as EditResult.Saved).day
        assertThat(day.exitAt).isEqualTo(MON.at(11, 30))
        assertThat(day.exitEdited).isTrue()
        assertThat(day.originalExitAt).isEqualTo(MON.at(11, 0))
    }

    // Use cases ---------------------------------------------------------------------------------

    @Test
    fun `an edited arrival is no longer moved by a late-delivered earlier signal`() = runTest {
        days.put(autoDay)
        edit(MON, DayEdit(t("08:10"), null, DayStatus.WORKING, null))
        clock.now = MON.at(12, 5)
        val result = process(Signal.GeofenceDwell(OFFICE_ID, at = MON.at(8, 2), receivedAt = clock.now))
        assertThat(result.arrivalChanged).isNull()
        assertThat(days.rows[MON]?.arrivalAt).isEqualTo(MON.at(8, 10))
        assertThat(log.events.first().type).isEqualTo(RawEventType.MANUAL)
    }

    @Test
    fun `undo clears the arrival and the same presence does not log it again`() = runTest {
        clock.now = MON.at(8, 7)
        process(Signal.WifiConnected(officeWifi, at = MON.at(8, 7)))
        assertThat(days.rows[MON]?.arrivalAt).isNotNull()

        clock.now = MON.at(8, 9)
        assertThat(undo(MON)).isTrue()
        assertThat(days.rows[MON]?.arrivalAt).isNull()
        assertThat(states.rows[MON]?.wifiPresence).isNull()

        // A late copy of the evidence that was undone is ignored ...
        val stale = process(Signal.WifiConnected(officeWifi, at = MON.at(8, 7), receivedAt = MON.at(8, 10)))
        assertThat(stale.arrivalChanged).isNull()
        // ... and the fences come back without the initial "already inside" trigger.
        assertThat(stale.plan?.geofence).isEqualTo(GeofenceArming.ARRIVAL)
        assertThat(stale.plan?.initialTrigger).isFalse()

        // A genuinely new connection later logs normally.
        clock.now = MON.at(9, 0)
        val fresh = process(Signal.WifiConnected(officeWifi, at = MON.at(9, 0)))
        assertThat(fresh.arrivalChanged?.at).isEqualTo(MON.at(9, 0))
    }

    @Test
    fun `undo without an arrival does nothing`() = runTest {
        assertThat(undo(MON)).isFalse()
        assertThat(log.events).isEmpty()
    }

    @Test
    fun `day off from the missed-entry notification is stored and logged`() = runTest {
        assertThat(mark(MON, DayStatus.WFH)).isTrue()
        assertThat(days.rows[MON]?.status).isEqualTo(DayStatus.WFH)
        assertThat(log.events.single().reason).isEqualTo("STATUS:2026-10-26:WFH")
    }

    @Test
    fun `a day with an arrival cannot be marked off from the notification`() = runTest {
        days.put(autoDay)
        assertThat(mark(MON, DayStatus.LEAVE)).isFalse()
        assertThat(days.rows[MON]?.status).isEqualTo(DayStatus.WORKING)
    }
}
