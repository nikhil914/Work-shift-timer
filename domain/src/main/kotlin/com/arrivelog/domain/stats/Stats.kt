package com.arrivelog.domain.stats

import com.arrivelog.domain.day.DayChip
import com.arrivelog.domain.day.DaySummaryCalculator
import com.arrivelog.domain.model.DetectionTuning
import com.arrivelog.domain.model.ScheduleSettings
import com.arrivelog.domain.model.WorkDay
import com.arrivelog.domain.schedule.ResolvedDay
import com.arrivelog.domain.schedule.ScheduleResolver
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

enum class StatsPeriod {
    /** Monday–Sunday (UAE working week). */
    WEEK,
    MONTH,
    ;

    fun range(anchor: LocalDate): ClosedRange<LocalDate> = when (this) {
        WEEK -> anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { it..it.plusDays(6) }
        MONTH -> anchor.withDayOfMonth(1)..anchor.with(TemporalAdjusters.lastDayOfMonth())
    }

    fun shift(anchor: LocalDate, steps: Long): LocalDate = when (this) {
        WEEK -> anchor.plusWeeks(steps)
        MONTH -> anchor.plusMonths(steps)
    }
}

/** One dot on the arrival chart. Minutes since midnight in the day's own zone. */
data class ArrivalPoint(val date: LocalDate, val minuteOfDay: Int, val expectedMinute: Int?, val late: Boolean)

data class WeekAverage(val weekStart: LocalDate, val averageArrival: LocalTime, val arrivals: Int)

data class DatedTime(val date: LocalDate, val time: LocalTime)

data class PeriodStats(
    val from: LocalDate,
    /** Last day counted: the period end, or today if the period is still running. */
    val to: LocalDate,
    /** Scheduled working days so far that were not marked off (day off / WFH / leave). */
    val workingDays: Int,
    /** Arrivals on scheduled working days (on time + late). Extra days are not rated. */
    val ratedArrivals: Int,
    val onTimeDays: Int,
    val lateDays: Int,
    /** Null when there is nothing to rate yet. */
    val onTimePercent: Int?,
    val averageLateMinutes: Int?,
    /** Working days that ended with neither an arrival nor a status. */
    val missingDays: Int,
    /** Average / earliest / latest over rated arrivals (scheduled working days). */
    val averageArrival: LocalTime?,
    val earliest: DatedTime?,
    val latest: DatedTime?,
    /** Days with both arrival and exit; open days are not guessed. */
    val totalWorked: Duration,
    val daysWithHours: Int,
    val points: List<ArrivalPoint>,
    val weekly: List<WeekAverage>,
)

/**
 * Stats for a week or month (spec section 4). Uses the same rules as the home chips
 * (DaySummaryCalculator), so "late" means the same thing everywhere.
 */
object StatsCalculator {
    fun compute(
        period: ClosedRange<LocalDate>,
        records: List<WorkDay>,
        schedule: ScheduleSettings,
        zone: ZoneId,
        now: Instant,
        tuning: DetectionTuning = DetectionTuning(),
    ): PeriodStats {
        val today = now.atZone(zone).toLocalDate()
        val from = period.start
        val to = minOf(period.endInclusive, today)
        val byDate = records.associateBy { it.date }
        val days = if (to.isBefore(from)) {
            emptyList()
        } else {
            generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }
                .map { DaySummaryCalculator.of(it, schedule, byDate[it], zone, now, tuning) }
                .toList()
        }

        val rated = days.filter { it.chip == DayChip.OnTime || it.chip is DayChip.Late }
        val late = rated.mapNotNull { it.chip as? DayChip.Late }
        // Times are averaged over scheduled working days only; a Saturday call-in would skew them.
        val arrivals = rated.mapNotNull { d -> d.arrivalAt?.let { DatedTime(d.date, it.atZone(d.zone).toLocalTime().withSecond(0).withNano(0)) } }
        val worked = days.filter { it.exitAt != null }.mapNotNull { it.worked }

        val points = days.mapNotNull { d ->
            val arrival = d.arrivalAt ?: return@mapNotNull null
            val expected = (ScheduleResolver.resolve(d.date, schedule) as? ResolvedDay.Working)?.profile?.expectedStart
            ArrivalPoint(d.date, arrival.atZone(d.zone).toLocalTime().minuteOfDay(), expected?.minuteOfDay(), d.chip is DayChip.Late)
        }
        val weekly = arrivals
            .groupBy { it.date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
            .toSortedMap()
            .map { (start, list) -> WeekAverage(start, averageTime(list.map { it.time }), list.size) }

        val workingDays = days.count { d ->
            ScheduleResolver.resolve(d.date, schedule) is ResolvedDay.Working && (d.chip !is DayChip.Status)
        }
        return PeriodStats(
            from = from,
            to = to,
            workingDays = workingDays,
            ratedArrivals = rated.size,
            onTimeDays = rated.size - late.size,
            lateDays = late.size,
            onTimePercent = if (rated.isEmpty()) null else Math.round((rated.size - late.size) * 100.0 / rated.size).toInt(),
            averageLateMinutes = if (late.isEmpty()) null else Math.round(late.map { it.minutes }.average()).toInt(),
            missingDays = days.count { it.chip == DayChip.Missing },
            averageArrival = arrivals.takeIf { it.isNotEmpty() }?.let { list -> averageTime(list.map { it.time }) },
            earliest = arrivals.minByOrNull { it.time },
            latest = arrivals.maxByOrNull { it.time },
            totalWorked = worked.fold(Duration.ZERO, Duration::plus),
            daysWithHours = worked.size,
            points = points,
            weekly = weekly,
        )
    }

    private fun LocalTime.minuteOfDay(): Int = hour * 60 + minute

    /** Arrival times never wrap midnight, so a plain mean of minutes is right. */
    private fun averageTime(times: List<LocalTime>): LocalTime {
        val mean = Math.round(times.map { it.minuteOfDay() }.average()).toInt()
        return LocalTime.of(mean / 60, mean % 60)
    }
}
