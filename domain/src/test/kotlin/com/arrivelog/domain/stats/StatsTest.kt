package com.arrivelog.domain.stats

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
import java.time.LocalDate
import java.time.LocalTime

class StatsTest {

    private val schedule = ScheduleSettings()
    private val wed: LocalDate = MON.plusDays(2)

    private fun day(date: LocalDate, h: Int, m: Int, exitH: Int? = null) = WorkDay(
        date = date,
        arrivalAt = date.at(h, m),
        arrivalSource = SignalSource.WIFI,
        exitAt = exitH?.let { date.at(it, 0) },
        zoneId = DUBAI,
    )

    @Test
    fun `week ranges run Monday to Sunday and months cover the calendar month`() {
        assertThat(StatsPeriod.WEEK.range(THU)).isEqualTo(MON..SUN)
        assertThat(StatsPeriod.MONTH.range(MON)).isEqualTo(LocalDate.of(2026, 10, 1)..LocalDate.of(2026, 10, 31))
        assertThat(StatsPeriod.MONTH.shift(MON, -1)).isEqualTo(LocalDate.of(2026, 9, 26))
    }

    @Test
    fun `a full week - on time percent, lateness, average, extremes and hours`() {
        val records = listOf(
            day(MON, 7, 55, exitH = 17), // on time
            day(TUE, 8, 10, exitH = 17), // late 10
            WorkDay(wed, status = DayStatus.WFH), // not counted
            day(THU, 8, 30), // late 30, no exit: no hours
            day(FRI, 7, 20, exitH = 12), // Friday expects 07:30: on time
            day(SAT, 9, 0, exitH = 11), // extra day: hours yes, rating no
        )
        val stats = StatsCalculator.compute(StatsPeriod.WEEK.range(MON), records, schedule, DUBAI, SUN.at(20, 0))

        assertThat(stats.workingDays).isEqualTo(4) // Mon, Tue, Thu, Fri (Wed was WFH)
        assertThat(stats.ratedArrivals).isEqualTo(4)
        assertThat(stats.onTimeDays).isEqualTo(2)
        assertThat(stats.lateDays).isEqualTo(2)
        assertThat(stats.onTimePercent).isEqualTo(50)
        assertThat(stats.averageLateMinutes).isEqualTo(20)
        assertThat(stats.earliest).isEqualTo(DatedTime(FRI, LocalTime.of(7, 20)))
        assertThat(stats.latest).isEqualTo(DatedTime(THU, LocalTime.of(8, 30)))
        // Working days only: (475 + 490 + 510 + 440) / 4 = 478.75 → 07:59
        assertThat(stats.averageArrival).isEqualTo(LocalTime.of(7, 59))
        assertThat(stats.totalWorked).isEqualTo(Duration.ofMinutes(545 + 530 + 280 + 120))
        assertThat(stats.daysWithHours).isEqualTo(4)
        assertThat(stats.missingDays).isEqualTo(0)
        assertThat(stats.points.map { it.date }).containsExactly(MON, TUE, THU, FRI, SAT).inOrder()
        assertThat(stats.points.first { it.date == TUE }.late).isTrue()
        assertThat(stats.points.first { it.date == SAT }.expectedMinute).isNull()
    }

    @Test
    fun `a running period stops at today and counts blank working days as missing`() {
        val stats = StatsCalculator.compute(StatsPeriod.WEEK.range(MON), listOf(day(MON, 8, 0)), schedule, DUBAI, wed.at(12, 0))
        assertThat(stats.to).isEqualTo(wed)
        assertThat(stats.missingDays).isEqualTo(2) // Tuesday, and Wednesday after its window closed
        assertThat(stats.workingDays).isEqualTo(3)
    }

    @Test
    fun `nothing logged yet gives empty, not zero percent`() {
        val stats = StatsCalculator.compute(StatsPeriod.MONTH.range(MON), emptyList(), schedule, DUBAI, MON.at(7, 0))
        assertThat(stats.onTimePercent).isNull()
        assertThat(stats.averageArrival).isNull()
        assertThat(stats.points).isEmpty()
    }

    @Test
    fun `weekly averages in a month`() {
        val records = listOf(day(MON.minusDays(7), 8, 0), day(TUE.minusDays(7), 8, 20), day(MON, 7, 40))
        val stats = StatsCalculator.compute(StatsPeriod.MONTH.range(MON), records, schedule, DUBAI, MON.at(12, 0))
        assertThat(stats.weekly).containsExactly(
            WeekAverage(MON.minusDays(7), LocalTime.of(8, 10), 2),
            WeekAverage(MON, LocalTime.of(7, 40), 1),
        ).inOrder()
    }
}
