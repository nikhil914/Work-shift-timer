package com.arrivelog.domain.usecase

import com.arrivelog.domain.engine.ArrivalDecisionEngine
import com.arrivelog.domain.engine.Decision
import com.arrivelog.domain.engine.DecisionContext
import com.arrivelog.domain.engine.ListenerPlan
import com.arrivelog.domain.engine.ListenerPlanner
import com.arrivelog.domain.engine.MissedEntryDecision
import com.arrivelog.domain.engine.Outcome
import com.arrivelog.domain.model.DetectionState
import com.arrivelog.domain.model.RawEvent
import com.arrivelog.domain.model.RawEventType
import com.arrivelog.domain.model.Signal
import com.arrivelog.domain.model.WorkDay
import com.arrivelog.domain.model.rawEventType
import com.arrivelog.domain.repository.DetectionStateRepository
import com.arrivelog.domain.repository.EventLogRepository
import com.arrivelog.domain.repository.SettingsRepository
import com.arrivelog.domain.repository.TransactionRunner
import com.arrivelog.domain.repository.WorkDayRepository
import com.arrivelog.domain.repository.WorkplaceRepository
import java.time.LocalDate
import javax.inject.Inject

data class SignalResult(
    val decision: Decision,
    val day: WorkDay?,
    /** Listener plan for *today* after this signal; null if the signal belonged to another day. */
    val plan: ListenerPlan?,
    /** Set when the stored arrival actually changed, i.e. when to (re)post the arrival notification. */
    val arrivalChanged: Outcome.ArrivalRecorded?,
    val exitChanged: Outcome.ExitRecorded?,
)

/** Loads everything the engine needs for one date. Must be called inside the transaction. */
class DayContextLoader @Inject constructor(
    private val settings: SettingsRepository,
    private val workplaces: WorkplaceRepository,
    private val workDays: WorkDayRepository,
    private val states: DetectionStateRepository,
) {
    suspend fun load(date: LocalDate): DecisionContext {
        val app = settings.current()
        return DecisionContext(
            date = date,
            settings = app.detection,
            schedule = app.schedule,
            workplaces = workplaces.getAll(),
            day = workDays.get(date),
            state = states.get(date) ?: DetectionState(date),
        )
    }
}

/**
 * The single entry point for every background signal: decide, persist the outcome, persist the
 * new state and write the raw event, all in one transaction so two receivers firing together
 * (geofence + Wi-Fi) cannot both log an arrival.
 */
class ProcessSignalUseCase @Inject constructor(
    private val engine: ArrivalDecisionEngine,
    private val planner: ListenerPlanner,
    private val loader: DayContextLoader,
    private val workDays: WorkDayRepository,
    private val states: DetectionStateRepository,
    private val eventLog: EventLogRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(signal: Signal): SignalResult = tx.inTransaction {
        val date = engine.localDateOf(signal.at)
        val ctx = loader.load(date)
        val decision = engine.onSignal(signal, ctx)
        val applied = applyOutcomes(date, decision, workDays, engine)
        states.put(decision.newState)
        eventLog.append(signal.toRawEvent(decision))

        val day = workDays.get(date)
        val plan = if (date == engine.today()) {
            planner.plan(ctx.copy(day = day, state = decision.newState))
        } else {
            null
        }
        SignalResult(decision, day, plan, applied.arrival, applied.exit)
    }
}

/** Commits a pending exit whose debounce elapsed. Used by the disarm job and when the app opens. */
class SettleDayUseCase @Inject constructor(
    private val engine: ArrivalDecisionEngine,
    private val loader: DayContextLoader,
    private val workDays: WorkDayRepository,
    private val states: DetectionStateRepository,
    private val eventLog: EventLogRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(date: LocalDate = engine.today()): Decision = tx.inTransaction {
        val ctx = loader.load(date)
        val decision = engine.settle(ctx)
        if (decision.newState != ctx.state) {
            applyOutcomes(date, decision, workDays, engine)
            states.put(decision.newState)
            eventLog.append(systemEvent(engine, "SETTLE:${decision.reasonText}"))
        }
        decision
    }
}

class CheckMissedEntryUseCase @Inject constructor(
    private val engine: ArrivalDecisionEngine,
    private val loader: DayContextLoader,
    private val eventLog: EventLogRepository,
) {
    suspend operator fun invoke(date: LocalDate = engine.today()): MissedEntryDecision {
        val decision = engine.onEntryWindowEnd(loader.load(date))
        eventLog.append(systemEvent(engine, "MISSED_ENTRY_CHECK:${decision.describe()}"))
        return decision
    }

    private fun MissedEntryDecision.describe(): String = when (this) {
        is MissedEntryDecision.NotYet -> "NOT_YET"
        is MissedEntryDecision.NotNeeded -> reason.name
        is MissedEntryDecision.Notify -> if (suggestedArrival != null) "NOTIFY_WITH_SUGGESTION" else "NOTIFY"
    }
}

private data class Applied(val arrival: Outcome.ArrivalRecorded?, val exit: Outcome.ExitRecorded?)

private suspend fun applyOutcomes(
    date: LocalDate,
    decision: Decision,
    workDays: WorkDayRepository,
    engine: ArrivalDecisionEngine,
): Applied {
    var arrival: Outcome.ArrivalRecorded? = null
    var exit: Outcome.ExitRecorded? = null
    for (outcome in decision.outcomes) {
        when (outcome) {
            is Outcome.ArrivalRecorded ->
                if (workDays.recordArrival(date, outcome.at, outcome.source, outcome.workplaceId, engine.zone)) arrival = outcome
            is Outcome.ExitRecorded ->
                if (workDays.recordExit(date, outcome.at, outcome.source)) exit = outcome
            is Outcome.ExitReopened -> {
                if (workDays.reopenExit(date, outcome.reentryAt)) exit = null
            }
            is Outcome.Noted, is Outcome.Ignored -> Unit
        }
    }
    return Applied(arrival, exit)
}

private fun systemEvent(engine: ArrivalDecisionEngine, reason: String): RawEvent {
    val now = engine.now()
    return RawEvent(at = now, receivedAt = now, type = RawEventType.SYSTEM, accepted = true, reason = reason)
}

fun Signal.toRawEvent(decision: Decision): RawEvent {
    val geofence = this as? Signal.Geofence
    val network = when (this) {
        is Signal.WifiConnected -> network
        is Signal.WifiLost -> network
        is Signal.WifiInRange -> networks.firstOrNull()
        is Signal.GeofenceExit -> connectedWifi
        else -> null
    }
    val ssid = when (this) {
        is Signal.WifiInRange -> networks.mapNotNull { it.ssid }.distinct().take(5).joinToString("|").ifEmpty { null }
        else -> network?.ssid
    }
    return RawEvent(
        at = at,
        receivedAt = receivedAt,
        type = rawEventType,
        workplaceId = geofence?.workplaceId,
        accuracyM = geofence?.accuracyM,
        ssid = ssid,
        bssid = network?.bssid,
        accepted = decision.accepted,
        reason = decision.reasonText,
    )
}
