package com.arrivelog.domain.day

import com.arrivelog.domain.model.DayStatus
import com.arrivelog.domain.model.DetectionTuning
import com.arrivelog.domain.model.ScheduleSettings
import com.arrivelog.domain.model.SignalSource
import com.arrivelog.domain.model.WorkDay
import com.arrivelog.domain.schedule.DayTimeline
import com.arrivelog.domain.schedule.ResolvedDay
import com.arrivelog.domain.schedule.ScheduleResolver
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** The status chip on the home card and on log rows. */
sealed interface DayChip {
    data object OnTime : DayChip
    data class Late(val minutes: Long) : DayChip

    /** Arrived on a day with no schedule (e.g. manual log on a weekend). */
    data object Arrived : DayChip
    data object NotYet : DayChip

    /** Working day, window closed, nothing logged and no status: needs the user's attention. */
    data object Missing : DayChip
    data class Status(val status: DayStatus) : DayChip
}

data class DaySummary(
    val date: LocalDate,
    val chip: DayChip,
    val arrivalAt: Instant?,
    val arrivalSource: SignalSource?,
    val exitAt: Instant?,
    /** Shown in the record's own zone, so a later zone change does not shift the times. */
    val zone: ZoneId,
    val worked: Duration?,
    val edited: Boolean,
    val note: String?,
    val greyed: Boolean,
    val profileName: String?,
)

object DaySummaryCalculator {
    fun of(
        date: LocalDate,
        schedule: ScheduleSettings,
        record: WorkDay?,
        zone: ZoneId,
        now: Instant,
        tuning: DetectionTuning = DetectionTuning(),
    ): DaySummary {
        val resolved = ScheduleResolver.resolve(date, schedule)
        val dayZone = record?.zoneId ?: zone
        val timeline = (resolved as? ResolvedDay.Working)?.let { DayTimeline.of(date, it.profile, dayZone, tuning) }
        val arrival = record?.arrivalAt
        val chip = when {
            arrival != null && timeline != null -> {
                val late = Duration.between(timeline.expectedStart, arrival).toMinutes()
                if (late > 0) DayChip.Late(late) else DayChip.OnTime
            }
            arrival != null -> DayChip.Arrived
            record != null && record.status != DayStatus.WORKING -> DayChip.Status(record.status)
            resolved is ResolvedDay.Off -> DayChip.Status(resolved.status)
            timeline != null && now.isBefore(timeline.missedEntryCheckAt) -> DayChip.NotYet
            else -> DayChip.Missing
        }
        val exit = record?.exitAt
        val worked = when {
            arrival == null -> null
            exit != null -> Duration.between(arrival, exit)
            date == now.atZone(dayZone).toLocalDate() -> Duration.between(arrival, now).takeIf { !it.isNegative }
            else -> null // past day without exit: open
        }
        val greyed = arrival == null && (chip is DayChip.Status)
        return DaySummary(
            date = date,
            chip = chip,
            arrivalAt = arrival,
            arrivalSource = record?.arrivalSource,
            exitAt = exit,
            zone = dayZone,
            worked = worked,
            edited = record?.editedManually ?: false,
            note = record?.note,
            greyed = greyed,
            profileName = (resolved as? ResolvedDay.Working)?.profile?.name,
        )
    }
}

data class WeekGroup(val weekStart: LocalDate, val days: List<DaySummary>, val worked: Duration)

object DayLogBuilder {
    /** UAE week: Monday–Sunday. Newest week first, newest day first within a week. */
    fun build(
        from: LocalDate,
        to: LocalDate,
        records: List<WorkDay>,
        schedule: ScheduleSettings,
        zone: ZoneId,
        now: Instant,
    ): List<WeekGroup> {
        val byDate = records.associateBy { it.date }
        val days = generateSequence(to) { it.minusDays(1) }
            .takeWhile { !it.isBefore(from) }
            .map { DaySummaryCalculator.of(it, schedule, byDate[it], zone, now) }
            .toList()
        return days.groupBy { it.date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
            .map { (start, list) -> WeekGroup(start, list, list.mapNotNull { it.worked }.fold(Duration.ZERO, Duration::plus)) }
            .sortedByDescending { it.weekStart }
    }

    /** Past working days with neither an arrival nor a status: the "never blank" safety net. */
    fun needingAttention(groups: List<WeekGroup>): List<DaySummary> =
        groups.flatMap { it.days }.filter { it.chip == DayChip.Missing }
}
