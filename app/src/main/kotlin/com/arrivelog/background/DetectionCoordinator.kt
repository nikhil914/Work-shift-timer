package com.arrivelog.background

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.PowerManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.arrivelog.domain.engine.ArrivalDecisionEngine
import com.arrivelog.domain.engine.DecisionContext
import com.arrivelog.domain.engine.GeofenceArming
import com.arrivelog.domain.engine.ExitReminder
import com.arrivelog.domain.engine.MissedEntryDecision
import com.arrivelog.domain.model.DayStatus
import com.arrivelog.domain.usecase.LogExitUseCase
import com.arrivelog.domain.usecase.UndoArrivalUseCase
import com.arrivelog.notify.Notifier
import com.arrivelog.domain.engine.GeofenceSignature
import com.arrivelog.domain.engine.ListenerPlan
import com.arrivelog.domain.engine.ListenerPlanner
import com.arrivelog.domain.engine.PlanReason
import com.arrivelog.domain.engine.WakePlanner
import com.arrivelog.domain.model.DetectionTuning
import com.arrivelog.domain.model.RawEvent
import com.arrivelog.domain.model.RawEventType
import com.arrivelog.domain.model.Signal
import com.arrivelog.domain.repository.DetectionStateRepository
import com.arrivelog.domain.repository.EventLogRepository
import com.arrivelog.domain.repository.SettingsRepository
import com.arrivelog.domain.usecase.CheckMissedEntryUseCase
import com.arrivelog.domain.usecase.DayContextLoader
import com.arrivelog.domain.usecase.ProcessSignalUseCase
import com.arrivelog.domain.usecase.SettleDayUseCase
import com.arrivelog.platform.DeviceStatus
import com.arrivelog.platform.WifiSource
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import com.arrivelog.domain.model.WifiNetwork
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Lets screens ask for a re-plan after a manual edit without depending on the whole coordinator. */
interface ReplanTrigger {
    suspend fun afterEdit(date: java.time.LocalDate)
}

/**
 * Everything background funnels through here, serialised by one mutex:
 *  - [sync]: settle exits, run the missed-entry check when due, reconcile registrations with the
 *    planner, schedule the single next wake, daily housekeeping.
 *  - [onGeofenceEvent] / [onWifiAvailable]: turn system intents into [Signal]s with their true
 *    event time, process them, then sync (which stands listeners down after an arrival).
 */
@Singleton
class DetectionCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: ArrivalDecisionEngine,
    private val listenerPlanner: ListenerPlanner,
    private val wakePlanner: WakePlanner,
    private val tuning: DetectionTuning,
    private val loader: DayContextLoader,
    private val processSignal: ProcessSignalUseCase,
    private val settleDay: SettleDayUseCase,
    private val checkMissedEntry: CheckMissedEntryUseCase,
    private val settingsRepo: SettingsRepository,
    private val states: DetectionStateRepository,
    private val eventLog: EventLogRepository,
    private val arming: ArmingStateStore,
    private val geofences: GeofenceRegistrar,
    private val wifiCallback: WifiCallbackRegistrar,
    private val wakes: WakeScheduler,
    private val wifi: WifiSource,
    private val deviceStatus: DeviceStatus,
    private val notifier: Notifier,
    private val undo: UndoArrivalUseCase,
    private val logExit: LogExitUseCase,
) : ReplanTrigger {
    private val mutex = Mutex()

    /** "Log now" / "Log 06:05" from the missed-entry notification (or any manual log). */
    suspend fun logArrival(at: Instant?) = mutex.withLock {
        val now = engine.now()
        handle(processSignal(Signal.Manual(at = at ?: now, receivedAt = now)))
        notifier.cancelMissed()
        syncLocked("MANUAL_LOG", force = false)
    }

    /** "Undo" on the arrival notification. Listeners come back if the window is still open. */
    suspend fun undoArrival(date: java.time.LocalDate) = mutex.withLock {
        if (undo(date)) notifier.cancelArrival()
        syncLocked("UNDO", force = false)
    }

    /** "Left now" on the exit reminder. */
    suspend fun logExitNow(date: java.time.LocalDate) = mutex.withLock {
        logExit(date)
        notifier.cancelExitReminder()
        syncLocked("MANUAL_EXIT", force = false)
    }

    /** After an edit-sheet save: resolve reminders and re-plan listeners for today. */
    override suspend fun afterEdit(date: java.time.LocalDate) = mutex.withLock {
        if (date == engine.today()) syncLocked("EDIT", force = false)
    }

    /** Post / update the arrival notification when today's stored arrival actually changed. */
    private fun handle(result: com.arrivelog.domain.usecase.SignalResult) {
        val arrival = result.arrivalChanged ?: return
        val day = result.day ?: return
        if (day.date != engine.today()) return
        notifier.cancelMissed()
        notifier.showArrival(day.date, arrival.at, day.zoneId ?: engine.zone, arrival.source, arrival.estimated)
    }

    suspend fun sync(reason: String, force: Boolean = false) = mutex.withLock { syncLocked(reason, force) }

    /** What should be registered right now (Health screen compares it with what is). Read-only. */
    suspend fun expectedPlan(): ListenerPlan {
        if (!settingsRepo.current().setupCompleted) return idlePlan()
        return listenerPlanner.plan(loader.load(engine.today()), engine.now())
    }

    suspend fun onGeofenceEvent(intent: Intent) = mutex.withLock {
        val event = GeofencingEvent.fromIntent(intent) ?: return@withLock
        val now = engine.now()
        if (event.hasError()) {
            val code = event.errorCode
            system(now, "GEOFENCE_ERROR:${GeofenceStatusCodes.getStatusCodeString(code)}", accepted = false)
            // GEOFENCE_NOT_AVAILABLE: Location (or Google Location Accuracy) was turned off and
            // Play services dropped our fences. Mark them invalid so the next sync re-adds them.
            arming.update { it.copy(geofenceSignature = ArmingState.SIGNATURE_INVALID, lastError = "GEOFENCE_$code", lastErrorAt = now) }
            syncLocked("GEOFENCE_ERROR", force = false)
            return@withLock
        }
        // True event time: the trigger location's fix time, never the (possibly minutes later)
        // processing time. Guard against a clock in the future.
        val location = event.triggeringLocation
        val at = location?.time?.takeIf { it > 0 }?.let(Instant::ofEpochMilli)?.coerceAtMost(now) ?: now
        val accuracy = location?.takeIf { it.hasAccuracy() }?.accuracy
        val transition = event.geofenceTransition
        val connectedAtExit: WifiNetwork? = if (transition == Geofence.GEOFENCE_TRANSITION_EXIT) wifi.currentConnection() else null

        event.triggeringGeofences.orEmpty().forEach { fence ->
            val workplaceId = GeofenceRegistrar.workplaceId(fence.requestId) ?: return@forEach
            val signal = when (transition) {
                Geofence.GEOFENCE_TRANSITION_ENTER -> Signal.GeofenceEnter(workplaceId, at, now, accuracy)
                Geofence.GEOFENCE_TRANSITION_DWELL -> Signal.GeofenceDwell(workplaceId, at, now, accuracy)
                Geofence.GEOFENCE_TRANSITION_EXIT -> Signal.GeofenceExit(workplaceId, at, now, accuracy, connectedAtExit)
                else -> null
            }
            signal?.let { handle(processSignal(it)) }
        }

        // Already awake: a free read of cached scan results, if the user enabled "in range".
        if (transition != Geofence.GEOFENCE_TRANSITION_EXIT) maybeReadInRange(now)
        syncLocked("GEOFENCE", force = false)
    }

    suspend fun onWifiAvailable(intent: Intent) = mutex.withLock {
        val now = engine.now()
        val network = intent.network()
        val connection = wifi.currentConnection(network) ?: WifiNetwork(null, null)
        handle(processSignal(Signal.WifiConnected(connection, at = now, receivedAt = now)))
        syncLocked("WIFI", force = false)
    }

    private suspend fun maybeReadInRange(now: Instant) {
        val app = settingsRepo.current()
        if (!app.detection.wifiInRangeCountsAsPresent || isPowerSave()) return
        if (!deviceStatus.granted(Manifest.permission.ACCESS_FINE_LOCATION)) return
        val networks = wifi.cachedScanResults().map { WifiNetwork(it.ssid, it.bssid) }
        if (networks.isNotEmpty()) handle(processSignal(Signal.WifiInRange(networks, at = now, receivedAt = now)))
    }

    private suspend fun syncLocked(reason: String, force: Boolean) {
        val now = engine.now()
        val today = engine.today()
        system(now, "WAKE:$reason${if (force) ":FORCE" else ""}")

        // Commit exits whose debounce elapsed (yesterday too, if the evening disarm was missed).
        settleDay(today.minusDays(1))
        settleDay(today)

        val app = settingsRepo.current()
        val ctx = loader.load(today)

        // Missed-entry check once when due, then one re-notify 60 min later if still unresolved.
        val timeline = engine.timelineFor(today, ctx.schedule)
        var state = arming.get()
        val unresolved = ctx.day?.arrivalAt == null && (ctx.day == null || ctx.day?.status == DayStatus.WORKING)
        if (app.setupCompleted && timeline != null && unresolved) {
            val firstDue = state.missedEntryCheckDate != today && !now.isBefore(timeline.missedEntryCheckAt)
            val renotifyDue = state.missedEntryCheckDate == today && state.missedEntryRenotifyDate != today &&
                !now.isBefore(timeline.missedEntryRenotifyAt)
            if (firstDue || renotifyDue) {
                val decision = checkMissedEntry(today)
                if (decision is MissedEntryDecision.Notify) {
                    notifier.showMissedEntry(today, decision.suggestedArrival, engine.zone, reminder = renotifyDue)
                }
                state = arming.update {
                    if (firstDue) it.copy(missedEntryCheckDate = today) else it.copy(missedEntryRenotifyDate = today)
                }
            }
        } else if (!unresolved) {
            notifier.cancelMissed() // arrival logged or day marked: the reminder is obsolete
        }

        // Opt-in "Still at work? Add exit time", once, at expected end + 2 h.
        if (ctx.day?.exitAt != null) notifier.cancelExitReminder()
        if (app.setupCompleted && timeline != null && state.exitReminderDate != today &&
            ExitReminder.isDue(timeline, ctx.settings, ctx.day, ctx.state, now)
        ) {
            notifier.showExitReminder(today)
            system(now, "EXIT_REMINDER")
            state = arming.update { it.copy(exitReminderDate = today) }
        }

        val plan = if (app.setupCompleted) listenerPlanner.plan(ctx, now) else idlePlan()
        reconcile(plan, ctx, state, now, force)

        val wake = if (app.setupCompleted) {
            wakePlanner.next(
                today, ctx.schedule, ctx.settings, ctx.workplaces, ctx.day,
                missedEntryCheckDone = state.missedEntryCheckDate == today,
                now = now,
                missedEntryRenotified = state.missedEntryRenotifyDate == today,
                pendingExitAt = ctx.state.pendingExit?.at,
                exitReminderSent = state.exitReminderDate == today,
            )
        } else {
            null
        }
        wakes.schedule(wake)
        arming.update { it.copy(nextWakeAt = wake?.at, nextWakeReason = wake?.reason?.name) }

        if (state.housekeepingDate != today) housekeeping(now, today)
    }

    private suspend fun reconcile(plan: ListenerPlan, ctx: DecisionContext, state: ArmingState, now: Instant, force: Boolean) {
        val signature = GeofenceSignature.of(plan, ctx.workplaces, tuning.loiteringDelay.toMillis())
        if (force || signature != state.geofenceSignature) {
            val result = geofences.register(plan, ctx.workplaces, now)
            result.onSuccess {
                system(now, if (plan.geofence == GeofenceArming.OFF) "GEOFENCE_REMOVED:${plan.reason}" else "GEOFENCE_ARMED:${plan.geofence}:${plan.reason}")
                arming.update {
                    it.copy(
                        geofenceSignature = signature,
                        geofenceArming = plan.geofence,
                        lastRegisteredAt = if (plan.geofence != GeofenceArming.OFF) now else it.lastRegisteredAt,
                    )
                }
            }.onFailure { e ->
                system(now, "GEOFENCE_FAILED:${e.message}", accepted = false)
                arming.update {
                    it.copy(geofenceSignature = ArmingState.SIGNATURE_INVALID, geofenceArming = GeofenceArming.OFF, lastError = e.message, lastErrorAt = now)
                }
            }
        }
        if (force || plan.wifiCallback != state.wifiArmed) {
            val result = if (plan.wifiCallback) wifiCallback.register() else wifiCallback.unregister()
            result.onSuccess {
                system(now, if (plan.wifiCallback) "WIFI_CALLBACK_ARMED" else "WIFI_CALLBACK_REMOVED")
                arming.update { it.copy(wifiArmed = plan.wifiCallback) }
            }.onFailure { e ->
                system(now, "WIFI_CALLBACK_FAILED:${e.message}", accepted = false)
                arming.update { it.copy(wifiArmed = false, lastError = e.message, lastErrorAt = now) }
            }
        }
    }

    /** Once a day: keep the event log to 90 days, drop old per-day engine state, ensure the watchdog. */
    private suspend fun housekeeping(now: Instant, today: java.time.LocalDate) {
        eventLog.deleteOlderThan(now.minus(Duration.ofDays(LOG_RETENTION_DAYS)))
        states.deleteBefore(today.minusDays(STATE_RETENTION_DAYS))
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WatchdogWorker.NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WatchdogWorker>(1, TimeUnit.DAYS).build(),
        )
        arming.update { it.copy(housekeepingDate = today) }
    }

    private fun idlePlan() = ListenerPlan(GeofenceArming.OFF, false, tuning.responsivenessDefaultMs, PlanReason.NO_WORKPLACES, null)

    private fun isPowerSave(): Boolean = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true

    private suspend fun system(now: Instant, reason: String, accepted: Boolean = true) {
        eventLog.append(RawEvent(at = now, receivedAt = now, type = RawEventType.SYSTEM, accepted = accepted, reason = reason))
    }

    private fun Intent.network(): Network? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(ConnectivityManager.EXTRA_NETWORK, Network::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(ConnectivityManager.EXTRA_NETWORK)
        }

    private companion object {
        const val LOG_RETENTION_DAYS = 90L
        const val STATE_RETENTION_DAYS = 7L
    }
}
