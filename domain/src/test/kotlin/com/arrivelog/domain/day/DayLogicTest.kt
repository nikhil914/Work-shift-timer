package com.arrivelog.domain.day

import com.arrivelog.domain.model.DayStatus
import com.arrivelog.domain.model.ScheduleSettings
import com.arrivelog.domain.model.SignalSource
import com.arrivelog.domain.model.WorkDay
import com.arrivelog.domain.testing.DUBAI
import com.arrivelog.domain.testing.FRI
import com.arrivelog.domain.testing.MON
import com.arrivelog.domain.testing.SAT
import com.arrivelog.domain.testing.SUN
import com.arrivelog.domain.testing.THU
import com.arrivelog.domain.testing.TUE
import com.arrivelog.domain.testing.at
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration
import java.time.ZoneId

class DayLogicTest {

    private val schedule = ScheduleSettings()

    private fun summary(day: WorkDay?, now: java.time.Instant, date: java.time.LocalDate = MON) =
        DaySummaryCalculator.of(date, schedule, day, DUBAI, now)

    @Test
    fun `chip - on time, late, not yet, missing`() {
        assertThat(summary(WorkDay(MON, arrivalAt = MON.at(8, 0)), MON.at(9, 0)).chip).isEqualTo(DayChip.OnTime)
        assertThat(summary(WorkDay(MON, arrivalAt = MON.at(8, 12)), MON.at(9, 0)).chip).isEqualTo(DayChip.Late(12))
        assertThat(summary(null, MON.at(9, 0)).chip).isEqualTo(DayChip.NotYet)
        assertThat(summary(null, MON.at(11, 10)).chip).isEqualTo(DayChip.Missing)
    }

    @Test
    fun `chip - friday uses 07_30 expected start`() {
        assertThat(summary(WorkDay(FRI, arrivalAt = FRI.at(7, 40)), FRI.at(9, 0), FRI).chip).isEqualTo(DayChip.Late(10))
    }

    @Test
    fun `chip - weekend, user status, manual weekend arrival`() {
        assertThat(summary(null, SAT.at(9, 0), SAT).chip).isEqualTo(DayChip.Status(DayStatus.WEEKEND))
        assertThat(summary(null, SAT.at(9, 0), SAT).greyed).isTrue()
        assertThat(summary(WorkDay(MON, status = DayStatus.WFH), MON.at(12, 0)).chip).isEqualTo(DayChip.Status(DayStatus.WFH))
        assertThat(summary(WorkDay(SAT, arrivalAt = SAT.at(10, 0)), SAT.at(11, 0), SAT).chip).isEqualTo(DayChip.Arrived)
    }

    @Test
    fun `worked time - live today, exit when known, open for past days`() {
        val today = summary(WorkDay(MON, arrivalAt = MON.at(8, 0)), MON.at(10, 30))
        assertThat(today.worked).isEqualTo(Duration.ofMinutes(150))
        val withExit = summary(WorkDay(MON, arrivalAt = MON.at(8, 0), exitAt = MON.at(17, 15)), TUE.at(9, 0))
        assertThat(withExit.worked).isEqualTo(Duration.ofMinutes(555))
        assertThat(summary(WorkDay(MON, arrivalAt = MON.at(8, 0)), TUE.at(9, 0)).worked).isNull()
    }

    @Test
    fun `times stay in the zone they were recorded in`() {
        val kolkata = ZoneId.of("Asia/Kolkata")
        val s = DaySummaryCalculator.of(MON, schedule, WorkDay(MON, arrivalAt = MON.at(8, 0), zoneId = DUBAI), kolkata, MON.at(9, 0))
        assertThat(s.zone).isEqualTo(DUBAI)
        assertThat(s.chip).isEqualTo(DayChip.OnTime)
    }

    @Test
    fun `day log groups by Monday-start week, newest first, and finds missing days`() {
        val records = listOf(
            WorkDay(MON, arrivalAt = MON.at(8, 5), exitAt = MON.at(17, 5)),
            WorkDay(TUE, status = DayStatus.LEAVE),
        )
        val groups = DayLogBuilder.build(MON.minusDays(7), THU, records, schedule, DUBAI, THU.at(12, 0))
        assertThat(groups.map { it.weekStart }).containsExactly(MON, MON.minusDays(7)).inOrder()
        assertThat(groups.first().days.map { it.date }).containsExactly(THU, MON.plusDays(2), TUE, MON).inOrder()
        assertThat(groups.first().worked).isEqualTo(Duration.ofHours(9))
        // Wednesday and Thursday (window closed) and all of last week's working days are missing.
        val missing = DayLogBuilder.needingAttention(groups).map { it.date }
        assertThat(missing).contains(MON.plusDays(2))
        assertThat(missing).contains(THU)
        assertThat(missing).doesNotContain(TUE)
        assertThat(missing).doesNotContain(SUN.minusDays(7))
    }

    @Test
    fun `edit keeps the audit trail and flags only changed fields`() {
        val auto = WorkDay(
            MON, arrivalAt = MON.at(8, 7), arrivalSource = SignalSource.WIFI, originalArrivalAt = MON.at(8, 7), zoneId = DUBAI,
        )
        val edited = DayEditor.apply(auto, DayEdit(MON.at(7, 55), MON.at(17, 0), DayStatus.WORKING, " left early "), DUBAI)
        assertThat(edited.arrivalAt).isEqualTo(MON.at(7, 55))
        assertThat(edited.originalArrivalAt).isEqualTo(MON.at(8, 7))
        assertThat(edited.arrivalEdited).isTrue()
        assertThat(edited.arrivalSource).isEqualTo(SignalSource.WIFI)
        assertThat(edited.exitSource).isEqualTo(SignalSource.MANUAL)
        assertThat(edited.exitEdited).isTrue()
        assertThat(edited.note).isEqualTo("left early")

        val noteOnly = DayEditor.apply(auto, DayEdit(auto.arrivalAt, null, DayStatus.WORKING, "x"), DUBAI)
        assertThat(noteOnly.arrivalEdited).isFalse()
        assertThat(noteOnly.editedManually).isFalse()
    }

    @Test
    fun `edit validation`() {
        assertThat(DayEditor.validate(DayEdit(null, MON.at(17, 0), DayStatus.WORKING, null))).containsExactly(EditProblem.EXIT_WITHOUT_ARRIVAL)
        assertThat(DayEditor.validate(DayEdit(MON.at(9, 0), MON.at(8, 0), DayStatus.WORKING, null))).containsExactly(EditProblem.EXIT_BEFORE_ARRIVAL)
        assertThat(DayEditor.validate(DayEdit(MON.at(9, 0), null, DayStatus.LEAVE, null))).containsExactly(EditProblem.TIMES_ON_NON_WORKING_DAY)
        assertThat(DayEditor.validate(DayEdit(null, null, DayStatus.DAY_OFF, null))).isEmpty()
    }

    @Test
    fun `undo clears arrival and exit but keeps the audit value`() {
        val auto = WorkDay(MON, arrivalAt = MON.at(6, 40), arrivalSource = SignalSource.GEOFENCE, originalArrivalAt = MON.at(6, 40), exitAt = MON.at(6, 55))
        val undone = DayEditor.undoArrival(auto)
        assertThat(undone.arrivalAt).isNull()
        assertThat(undone.exitAt).isNull()
        assertThat(undone.originalArrivalAt).isEqualTo(MON.at(6, 40))
    }
}
