package com.arrivelog.domain.engine

import com.arrivelog.domain.model.DayStatus
import com.arrivelog.domain.model.DetectionTuning
import com.arrivelog.domain.schedule.DayTimeline
import com.arrivelog.domain.schedule.ResolvedDay
import com.arrivelog.domain.schedule.ScheduleResolver
import java.time.Clock
import java.time.Instant
import javax.inject.Inject

enum class GeofenceArming {
    OFF,

    /** ENTER + DWELL with initial trigger, until arrival or the window (plus grace) closes. */
    ARRIVAL,

    /** ENTER + EXIT, no initial trigger: exit detection and re-entry cancellation only. */
    PRESENCE,
}

enum class PlanReason {
    NON_WORKING_DAY,
    DAY_MARKED_NOT_WORKING,
    NO_WORKPLACES,
    BEFORE_ARM_TIME,
    AWAITING_ARRIVAL,
    ENTRY_WINDOW_CLOSED,
    ARRIVED_EXIT_TRACKING_OFF,
    TRACKING_EXIT,
    PAST_DISARM,
}

/**
 * Which listeners should exist right now. The background layer (Phase 3) diffs this against what is
 * registered. This is where the battery budget of section 5b is enforced: anything not listed here
 * is not registered.
 */
data class ListenerPlan(
    val geofence: GeofenceArming,
    val wifiCallback: Boolean,
    val responsivenessMs: Int,
    val reason: PlanReason,
    /** When this plan is next expected to change by itself (for scheduling), if ever today. */
    val nextChangeAt: Instant?,
    /**
     * ARRIVAL fences report "already inside" when registered. Off after Undo: re-arming must not
     * log the arrival the user just undid; a fresh ENTER (or Wi-Fi connection) is needed instead.
     */
    val initialTrigger: Boolean = false,
) {
    val isIdle: Boolean get() = geofence == GeofenceArming.OFF && !wifiCallback
}

class ListenerPlanner @Inject constructor(
    private val clock: Clock,
    private val tuning: DetectionTuning,
) {
    fun plan(ctx: DecisionContext, now: Instant = clock.instant()): ListenerPlan {
        val resolved = ScheduleResolver.resolve(ctx.date, ctx.schedule)
        val timeline = (resolved as? ResolvedDay.Working)
            ?.let { DayTimeline.of(ctx.date, it.profile, clock.zone, tuning) }
            ?: return off(PlanReason.NON_WORKING_DAY)
        val day = ctx.day
        if (day != null && day.arrivalAt == null && day.status != DayStatus.WORKING) {
            return off(PlanReason.DAY_MARKED_NOT_WORKING)
        }
        if (ctx.workplaces.isEmpty()) return off(PlanReason.NO_WORKPLACES)

        val mode = ctx.settings.mode
        val responsiveness = if (ctx.settings.fasterDetection) tuning.responsivenessFastMs else tuning.responsivenessDefaultMs

        if (day?.arrivalAt == null) {
            return when {
                now.isBefore(timeline.armAt) -> off(PlanReason.BEFORE_ARM_TIME, next = timeline.armAt)
                !now.isBefore(timeline.missedEntryCheckAt) -> off(PlanReason.ENTRY_WINDOW_CLOSED)
                else -> ListenerPlan(
                    geofence = if (mode.usesGeofence) GeofenceArming.ARRIVAL else GeofenceArming.OFF,
                    wifiCallback = mode.usesWifi,
                    responsivenessMs = responsiveness,
                    reason = PlanReason.AWAITING_ARRIVAL,
                    nextChangeAt = timeline.missedEntryCheckAt,
                    initialTrigger = mode.usesGeofence && ctx.state.ignoreEvidenceBefore == null,
                )
            }
        }

        // Arrived. With exit tracking off the rest of the day must cost nothing.
        if (!ctx.settings.exitTrackingEnabled) return off(PlanReason.ARRIVED_EXIT_TRACKING_OFF)
        if (!now.isBefore(timeline.disarmAt)) return off(PlanReason.PAST_DISARM)
        return ListenerPlan(
            // Exits always come from the geofence, also in Wi-Fi-only mode (decision 26 Oct 2026).
            geofence = GeofenceArming.PRESENCE,
            // Kept for re-entry: the PendingIntent Wi-Fi callback only reports "available", never "lost".
            wifiCallback = mode.usesWifi,
            responsivenessMs = tuning.responsivenessDefaultMs,
            reason = PlanReason.TRACKING_EXIT,
            nextChangeAt = timeline.disarmAt,
        )
    }

    private fun off(reason: PlanReason, next: Instant? = null) =
        ListenerPlan(GeofenceArming.OFF, wifiCallback = false, responsivenessMs = tuning.responsivenessDefaultMs, reason = reason, nextChangeAt = next)
}
