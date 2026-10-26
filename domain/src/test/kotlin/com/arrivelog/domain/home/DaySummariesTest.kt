package com.arrivelog.domain.home

import com.arrivelog.domain.model.DayStatus
import com.arrivelog.domain.model.DetectionTuning
import com.arrivelog.domain.model.ScheduleSettings
import com.arrivelog.domain.model.SignalSource
import com.arrivelog.domain.model.WorkDay
import com.arrivelog.domain.testing.FRI
import com.arrivelog.domain.testing.MON
import com.arrivelog.domain.testing.MutableClock
import com.arrivelog.domain.testing.SAT
import com.arrivelog.domain.testing.SUN
import com.arrivelog.domain.testing.TUE
import com.arrivelog.domain.testing.at
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration

class DaySummariesTest {

    private val clock = MutableClock(TUE.at(9, 0))
    private val summarizer = DaySummarizer(clock, DetectionTuning())
    private val schedule = ScheduleSettings()

    private fun arrived(date: java.time.LocalDate, h: Int, m: Int) =
        WorkDay(date, arrivalAt = date.at(h, m), arrivalSource = SignalSource.WIFI)

    @Test
    fun `on time and late are measured against the expected start`() {
        assertThat(summarizer.summarize(MON, schedule, arrived(MON, 8, 0)).badge).isEqualTo(DayBadge.OnTime)
        assertThat(summarizer.summarize(MON, schedule, arrived(MON, 8, 12)).badge).isEqualTo(DayBadge.Late(12))
        // Friday expects 07:30 (profile B).
        assertThat(summarizer.summarize(FRI, schedule, arrived(FRI, 7, 45)).badge).isEqualTo(DayBadge.Late(15))
    }

    @Test
    fun `today before the window closes is not yet, afterwards missing`() {
        clock.now = TUE.at(9, 0)
        assertThat(summarizer.summarize(TUE, schedule, null).badge).isEqualTo(DayBadge.NotYet)
        clock.now = TUE.at(11, 0)
        assertThat(summarizer.summarize(TUE, schedule, null).badge).isEqualTo(DayBadge.Missing)
        assertThat(summarizer.summarize(TUE, schedule, null).needsAttention).isTrue()
    }

    @Test
    fun `a past working day with nothing recorded needs attention`() {
        assertThat(summarizer.summarize(MON, schedule, null).badge).isEqualTo(DayBadge.Missing)
    }

    @Test
    fun `weekends and statuses set by the user are off days`() {
        assertThat(summarizer.summarize(SAT, schedule, null).badge).isEqualTo(DayBadge.Off(DayStatus.WEEKEND))
        assertThat(summarizer.summarize(SAT, schedule, null).isOffDay).isTrue()
        val wfh = summarizer.summarize(MON, schedule, WorkDay(MON, status = DayStatus.WFH))
        assertThat(wfh.badge).isEqualTo(DayBadge.Off(DayStatus.WFH))
        assertThat(wfh.needsAttention).isFalse()
        val leave = schedule.copy(daysOff = mapOf(MON to DayStatus.LEAVE))
        assertThat(summarizer.summarize(MON, leave, null).badge).isEqualTo(DayBadge.Off(DayStatus.LEAVE))
    }

    @Test
    fun `an arrival on an off day is an extra day`() {
        assertThat(summarizer.summarize(SAT, schedule, arrived(SAT, 9, 0)).badge).isEqualTo(DayBadge.ExtraDay)
    }

    @Test
    fun `worked time is exit minus arrival, or live while still at work today`() {
        val closed = arrived(MON, 8, 0).copy(exitAt = MON.at(16, 30))
        assertThat(summarizer.summarize(MON, schedule, closed).worked).isEqualTo(Duration.ofMinutes(510))

        clock.now = TUE.at(10, 15)
        val open = summarizer.summarize(TUE, schedule, arrived(TUE, 8, 0))
        assertThat(open.atWorkNow).isTrue()
        assertThat(open.worked).isEqualTo(Duration.ofMinutes(135))

        // A past day without exit stays open ("—"), it does not keep counting.
        assertThat(summarizer.summarize(MON, schedule, arrived(MON, 8, 0)).worked).isNull()
    }

    @Test
    fun `log lists every day newest first grouped by Monday weeks`() {
        val weeks = summarizer.log(
            from = MON.minusDays(3),
            to = TUE,
            schedule = schedule,
            days = listOf(arrived(MON, 8, 0).copy(exitAt = MON.at(17, 0))),
        )
        assertThat(weeks.map { it.weekStart }).containsExactly(MON, MON.minusDays(7)).inOrder()
        assertThat(weeks[0].days.map { it.date }).containsExactly(TUE, MON).inOrder()
        assertThat(weeks[0].worked).isEqualTo(Duration.ofHours(9))
        assertThat(weeks[1].days.map { it.date }).containsExactly(SUN.minusDays(7), SAT.minusDays(7), FRI.minusDays(7)).inOrder()
        assertThat(weeks[1].days.first().isOffDay).isTrue()
    }
}
