package com.arrivelog.domain.engine

import com.arrivelog.domain.model.DayStatus
import com.arrivelog.domain.model.DetectionTuning
import com.arrivelog.domain.model.ScheduleSettings
import com.arrivelog.domain.model.DetectionSettings
import com.arrivelog.domain.model.WorkDay
import com.arrivelog.domain.model.Workplace
import com.arrivelog.domain.schedule.DayTimeline
import com.arrivelog.domain.schedule.ResolvedDay
import com.arrivelog.domain.schedule.ScheduleResolver
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

enum class WakeReason { ARM, MISSED_ENTRY_CHECK, MISSED_ENTRY_RENOTIFY, DISARM, NEXT_WORKING_DAY }

data class Wake(val at: Instant, val reason: WakeReason)

/**
 * The single next moment the app must wake up by itself. At most three per working day (arm,
 * missed-entry check, disarm); none on days that are off. Everything else is event-driven.
 */
class WakePlanner @Inject constructor(
    private val clock: Clock,
    private val tuning: DetectionTuning,
) {
    fun next(
        today: LocalDate,
        schedule: ScheduleSettings,
        settings: DetectionSettings,
        workplaces: List<Workplace>,
        todayRecord: WorkDay?,
        missedEntryCheckDone: Boolean,
        now: Instant = clock.instant(),
        missedEntryRenotified: Boolean = false,
    ): Wake? {
        if (workplaces.isEmpty()) return null
        timeline(today, schedule)?.let { t ->
            todayCandidates(t, settings, todayRecord, missedEntryCheckDone, missedEntryRenotified).filter { it.at.isAfter(now) }.minByOrNull { it.at }?.let { return it }
        }
        // Next working day's arm time (holidays and OFF days skipped). Two weeks covers any leave
        // block entered so far; the daily watchdog re-plans anyway.
        for (offset in 1L..LOOKAHEAD_DAYS) {
            val t = timeline(today.plusDays(offset), schedule) ?: continue
            if (t.armAt.isAfter(now)) return Wake(t.armAt, WakeReason.NEXT_WORKING_DAY)
        }
        return null
    }

    private fun todayCandidates(t: DayTimeline, settings: DetectionSettings, day: WorkDay?, missedDone: Boolean, renotified: Boolean): List<Wake> = buildList {
        val markedOff = day != null && day.arrivalAt == null && day.status != DayStatus.WORKING
        if (markedOff) return@buildList
        add(Wake(t.armAt, WakeReason.ARM))
        if (day?.arrivalAt == null && !missedDone) add(Wake(t.missedEntryCheckAt, WakeReason.MISSED_ENTRY_CHECK))
        // Spec: re-notify once, 60 min after the first reminder, if still unresolved.
        if (day?.arrivalAt == null && missedDone && !renotified) add(Wake(t.missedEntryRenotifyAt, WakeReason.MISSED_ENTRY_RENOTIFY))
        if (day?.arrivalAt != null && settings.exitTrackingEnabled) add(Wake(t.disarmAt, WakeReason.DISARM))
    }

    private fun timeline(date: LocalDate, schedule: ScheduleSettings): DayTimeline? =
        (ScheduleResolver.resolve(date, schedule) as? ResolvedDay.Working)?.let { DayTimeline.of(date, it.profile, clock.zone, tuning) }

    private companion object {
        const val LOOKAHEAD_DAYS = 31L
    }
}

/**
 * Everything that, if changed, requires geofences to be registered again. Comparing signatures
 * avoids re-registering (and waking location) on every sync.
 */
object GeofenceSignature {
    fun of(plan: ListenerPlan, workplaces: List<Workplace>, loiteringMs: Long): String =
        if (plan.geofence == GeofenceArming.OFF) {
            "OFF"
        } else {
            buildString {
                append(plan.geofence.name).append('|').append(plan.responsivenessMs).append('|').append(loiteringMs)
                workplaces.sortedBy { it.id }.forEach { w ->
                    append('|').append(w.id).append(':').append(w.latitude).append(',').append(w.longitude).append(',').append(w.radiusMeters)
                }
            }
        }
}
