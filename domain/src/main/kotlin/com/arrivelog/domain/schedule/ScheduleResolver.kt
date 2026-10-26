package com.arrivelog.domain.schedule

import com.arrivelog.domain.model.DayStatus
import com.arrivelog.domain.model.DetectionTuning
import com.arrivelog.domain.model.ScheduleProfile
import com.arrivelog.domain.model.ScheduleSettings
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

sealed interface ResolvedDay {
    val date: LocalDate

    data class Working(override val date: LocalDate, val profile: ScheduleProfile) : ResolvedDay

    /** [status] is WEEKEND (weekday set to OFF), HOLIDAY or LEAVE. */
    data class Off(override val date: LocalDate, val status: DayStatus) : ResolvedDay
}

object ScheduleResolver {
    fun resolve(date: LocalDate, schedule: ScheduleSettings): ResolvedDay {
        schedule.daysOff[date]?.let { return ResolvedDay.Off(date, it) }
        val profile = schedule.profile(schedule.assignments[date.dayOfWeek])
        return if (profile != null && profile.isValid) {
            ResolvedDay.Working(date, profile)
        } else {
            ResolvedDay.Off(date, DayStatus.WEEKEND)
        }
    }
}

/**
 * Every moment that matters for one working day, as Instants in the zone the day is evaluated in.
 * Local times that fall into a DST gap are shifted forward by the gap (java.time default), and
 * ambiguous ones take the earlier offset, so the window is always well-defined.
 */
data class DayTimeline(
    val date: LocalDate,
    val zone: ZoneId,
    val profile: ScheduleProfile,
    val armAt: Instant,
    val windowStart: Instant,
    val windowEnd: Instant,
    /** windowEnd + grace: missed-entry check; also disarms when nothing was detected. */
    val missedEntryCheckAt: Instant,
    val missedEntryRenotifyAt: Instant,
    val expectedStart: Instant,
    val expectedEnd: Instant,
    val exitReminderAt: Instant,
    /** After arrival with exit tracking on, listeners come down here. */
    val disarmAt: Instant,
) {
    fun isInEntryWindow(t: Instant): Boolean = !t.isBefore(windowStart) && t.isBefore(windowEnd)

    /**
     * Auto arrivals are accepted from arm time, not window start: if you are already inside when
     * the window opens, the earlier true time is logged (decision 26 Oct 2026). Nothing earlier
     * than arm time is delivered in normal operation, since no listener is registered before it.
     */
    fun acceptsArrivalAt(t: Instant): Boolean = !t.isBefore(armAt) && t.isBefore(windowEnd)

    companion object {
        fun of(date: LocalDate, profile: ScheduleProfile, zone: ZoneId, tuning: DetectionTuning): DayTimeline {
            val windowStart = date.at(profile.entryWindowStart, zone)
            val windowEnd = date.at(profile.entryWindowEnd, zone)
            val expectedEnd = date.at(profile.expectedEnd, zone)
            val missedCheck = windowEnd.plus(tuning.windowCloseGrace)
            val disarm = expectedEnd.plus(tuning.disarmAfterExpectedEnd)
            return DayTimeline(
                date = date,
                zone = zone,
                profile = profile,
                armAt = windowStart.minus(tuning.armLeadTime),
                windowStart = windowStart,
                windowEnd = windowEnd,
                missedEntryCheckAt = missedCheck,
                missedEntryRenotifyAt = missedCheck.plus(tuning.missedEntryRenotifyAfter),
                expectedStart = date.at(profile.expectedStart, zone),
                expectedEnd = expectedEnd,
                exitReminderAt = expectedEnd.plus(tuning.exitReminderAfterExpectedEnd),
                disarmAt = if (disarm.isAfter(missedCheck)) disarm else missedCheck,
            )
        }

        private fun LocalDate.at(time: LocalTime, zone: ZoneId): Instant =
            ZonedDateTime.of(this, time, zone).toInstant()
    }
}
