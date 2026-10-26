package com.arrivelog.domain.engine

import com.arrivelog.domain.model.DetectionSettings
import com.arrivelog.domain.model.DetectionState
import com.arrivelog.domain.model.ScheduleSettings
import com.arrivelog.domain.model.SignalSource
import com.arrivelog.domain.model.WorkDay
import com.arrivelog.domain.model.Workplace
import java.time.Instant
import java.time.LocalDate

/** Everything the engine needs to decide about one day. Built by the use case, never by the engine. */
data class DecisionContext(
    val date: LocalDate,
    val settings: DetectionSettings,
    val schedule: ScheduleSettings,
    val workplaces: List<Workplace>,
    val day: WorkDay?,
    val state: DetectionState,
)

/** Stable codes stored in event_log.reason. The UI maps them to string resources. */
enum class DecisionReason {
    ARRIVAL_RECORDED,
    ARRIVAL_MOVED_EARLIER,
    MANUAL_ARRIVAL,
    AWAITING_DWELL,
    AWAITING_WIFI,
    AWAITING_GEOFENCE,
    AWAITING_SIGNAL,
    WORKPLACE_MISMATCH,
    BEFORE_WINDOW,
    AFTER_WINDOW,
    NON_WORKING_DAY,
    DAY_MARKED_NOT_WORKING,
    ALREADY_ARRIVED,
    MODE_EXCLUDES_SIGNAL,
    UNKNOWN_WORKPLACE,
    SSID_UNKNOWN,
    SSID_NOT_OFFICE,
    BSSID_MISMATCH,
    IN_RANGE_DISABLED,
    NO_WIFI_PRESENCE,
    EVIDENCE_UNDONE,
    EXIT_PENDING,
    EXIT_CANCELLED_REENTRY,
    EXIT_RECORDED,
    EXIT_REOPENED,
    EXIT_TRACKING_OFF,
    EXIT_BEFORE_ARRIVAL,
    EXIT_NOT_LATER,
    EXIT_EDITED_MANUALLY,
    EXIT_CONTRADICTED_BY_WIFI,
    STALE_EXIT,
    PRESENCE_UPDATED,
    NO_CHANGE,
}

sealed interface Outcome {
    val reason: DecisionReason

    data class ArrivalRecorded(
        val at: Instant,
        val source: SignalSource,
        val workplaceId: Long?,
        /** Time back-calculated from DWELL because the ENTER was never seen. */
        val estimated: Boolean,
        /** A late-delivered signal proved an earlier arrival than the one already logged. */
        val replacesLaterRecord: Boolean,
    ) : Outcome {
        override val reason: DecisionReason
            get() = when {
                source == SignalSource.MANUAL -> DecisionReason.MANUAL_ARRIVAL
                replacesLaterRecord -> DecisionReason.ARRIVAL_MOVED_EARLIER
                else -> DecisionReason.ARRIVAL_RECORDED
            }
    }

    data class ExitRecorded(val at: Instant, val source: SignalSource) : Outcome {
        override val reason: DecisionReason get() = DecisionReason.EXIT_RECORDED
    }

    /** Back inside after a committed auto exit: the day is open again ("last exit wins"). */
    data class ExitReopened(val reentryAt: Instant) : Outcome {
        override val reason: DecisionReason get() = DecisionReason.EXIT_REOPENED
    }

    /** The signal was used (state changed) but nothing is written to the day yet. */
    data class Noted(override val reason: DecisionReason) : Outcome

    data class Ignored(override val reason: DecisionReason) : Outcome
}

data class Decision(
    val outcomes: List<Outcome>,
    val newState: DetectionState,
) {
    val accepted: Boolean get() = outcomes.any { it !is Outcome.Ignored }
    val reasonText: String get() = outcomes.joinToString(",") { it.reason.name }
    val arrival: Outcome.ArrivalRecorded? get() = outcomes.filterIsInstance<Outcome.ArrivalRecorded>().lastOrNull()
    val exit: Outcome.ExitRecorded? get() = outcomes.filterIsInstance<Outcome.ExitRecorded>().lastOrNull()
}

sealed interface MissedEntryDecision {
    /** Job ran early (inexact scheduling); run again at [checkAt]. */
    data class NotYet(val checkAt: Instant) : MissedEntryDecision
    data class NotNeeded(val reason: DecisionReason) : MissedEntryDecision

    /**
     * Post "No arrival detected today". [suggestedArrival] is the earliest presence evidence of the
     * day that could not be auto-logged (e.g. you were already inside before the window opened).
     */
    data class Notify(val suggestedArrival: Instant?) : MissedEntryDecision
}
