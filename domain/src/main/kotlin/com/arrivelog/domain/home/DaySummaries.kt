package com.arrivelog.domain.home

import com.arrivelog.domain.model.DayStatus
import com.arrivelog.domain.model.DetectionTuning
import com.arrivelog.domain.model.ScheduleSettings
import com.arrivelog.domain.model.WorkDay
import com.arrivelog.domain.schedule.DayTimeline
import com.arrivelog.domain.schedule.ResolvedDay
import com.arrivelog.domain.schedule.ScheduleResolver
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

/** The status chip on the home card and the day log. */
sealed interface DayBadge {
    data object OnTime : DayBadge
    data class Late(val minutes: Long) : DayBadge
    data object NotYet : DayBadge

    /** Working day, entry window over, no arrival and no status: needs a decision ("never blank"). */
    data object Missing : DayBadge

    /** WEEKEND / HOLIDAY / LEAVE from the schedule, or DAY_OFF / WFH / LEAVE set by the user. */
    data class Off(val status: DayStatus) : DayBadge

    /** An arrival on a day the schedule has off (e.g. a Saturday shift logged by hand). */
    data object ExtraDay : DayBadge
}

data class DaySummary(
    val date: LocalDate,
    val day: WorkDay?,
    val badge: DayBadge,
    val expectedStart: Instant?,
    /** Exit − arrival; for today's open day, the live time so far. Null when unknown. */
    val worked: Duration?,
    /** Arrived today and no exit yet: the home card shows a ticking counter. */
    val atWorkNow: Boolean,
) {
    val needsAttention: Boolean get() = badge == DayBadge.Missing

    /** Greyed in the log: off days with nothing recorded. */
    val isOffDay: Boolean get() = badge is DayBadge.Off && day?.arrivalAt == null
}

data class WeekGroup(
    /** Monday (UAE working week Mon–Fri). */
    val weekStart: LocalDate,
    /** Newest first. */
    val days: List<DaySummary>,
    val worked: Duration,
)

/** Pure presentation rules for days, shared by the home card, the log and (later) stats. */
class DaySummarizer @Inject constructor(
    private val clock: Clock,
    private val tuning: DetectionTuning,
) {
    fun summarize(date: LocalDate, schedule: ScheduleSettings, day: WorkDay?, now: Instant = clock.instant()): DaySummary {
        val resolved = ScheduleResolver.resolve(date, schedule)
        val timeline = (resolved as? ResolvedDay.Working)?.let { DayTimeline.of(date, it.profile, clock.zone, tuning) }
        val today = now.atZone(clock.zone).toLocalDate()
        val arrival = day?.arrivalAt
        val exit = day?.exitAt

        val badge = when {
            arrival != null && timeline == null -> DayBadge.ExtraDay
            arrival != null && timeline != null -> {
                val late = Duration.between(timeline.expectedStart, arrival).toMinutes()
                if (late >= 1) DayBadge.Late(late) else DayBadge.OnTime
            }
            day != null && day.status != DayStatus.WORKING -> DayBadge.Off(day.status)
            resolved is ResolvedDay.Off -> DayBadge.Off(resolved.status)
            timeline == null -> DayBadge.Off(DayStatus.WEEKEND)
            date.isBefore(today) || !now.isBefore(timeline.windowEnd) -> DayBadge.Missing
            else -> DayBadge.NotYet
        }
        val atWorkNow = arrival != null && exit == null && date == today && !now.isBefore(arrival)
        val worked = when {
            arrival != null && exit != null && exit.isAfter(arrival) -> Duration.between(arrival, exit)
            atWorkNow && arrival != null -> Duration.between(arrival, now)
            else -> null
        }
        return DaySummary(date, day, badge, timeline?.expectedStart, worked, atWorkNow)
    }

    /** Every date from [to] back to [from] (newest first), grouped by week. Days without a row are included. */
    fun log(from: LocalDate, to: LocalDate, schedule: ScheduleSettings, days: List<WorkDay>, now: Instant = clock.instant()): List<WeekGroup> {
        if (to.isBefore(from)) return emptyList()
        val byDate = days.associateBy { it.date }
        return generateSequence(to) { it.minusDays(1) }
            .takeWhile { !it.isBefore(from) }
            .map { summarize(it, schedule, byDate[it], now) }
            .groupBy { weekStart(it.date) }
            .map { (start, list) -> WeekGroup(start, list, list.mapNotNull { it.worked }.fold(Duration.ZERO, Duration::plus)) }
    }

    companion object {
        fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }
}
