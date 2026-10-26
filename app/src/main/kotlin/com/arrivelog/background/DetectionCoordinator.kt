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
import com.arrivelog.domain.engine.GeofenceSignature
import com.arrivelog.domain.engine.ListenerPlan
import com.arrivelog.domain.engine.ListenerPlanner
import com.arrivelog.domain.engine.MissedEntryDecision
import com.arrivelog.domain.engine.PlanReason
import com.arrivelog.domain.engine.WakePlanner
import com.arrivelog.domain.model.DayStatus
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
import com.arrivelog.domain.usecase.SignalResult
import com.arrivelog.domain.usecase.UndoArrivalUseCase
import com.arrivelog.notifications.Notifier
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
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything background funnels through here, serialised by one mutex:
 *  - [sync]: settle exits, run the missed-entry check when due, reconcile registrations with the
 *    planner, schedule the single next wake, daily housekeeping.
 *  - [onGeofenceEvent] / [onWifiAvailable]: turn system intents into [Signal]s with their true
 *    event time, process them, then sync (which stands listeners down after an arrival).
 *  - [logNow] / [undoArrival] / [afterUserEdit]: user actions from notifications and Home.
 * Notifications are posted from here, so every path (background, notification action, app) agrees.
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
    private val undo: UndoArrivalUseCase,
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
) : DetectionActions {
    private val mutex = Mutex()

    suspend fun sync(reason: String, force: Boolean = false) = mutex.withLock { syncLocked(reason, force) }

    /** "Log now" (notification or Home): a manual arrival at this moment, any day. */
    override suspend fun logNow(): Boolean = mutex.withLock {
        val now = engine.now()
        val result = process(Signal.Manual(at = now, receivedAt = now))
        syncLocked("LOG_NOW", force = false)
        result.arrivalChanged != null
    }

    suspend fun undoArrival(date: LocalDate) = mutex.withLock {
        if (undo(date)) notifier.cancelArrival()
        syncLocked("UNDO", force = false)
    }

    /** Edit sheet saved: the plan may change (arrival cleared or added, day marked off). */
    override suspend fun afterUserEdit(date: LocalDate) = mutex.withLock { syncLocked("USER_EDIT", force = false) }

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
            signal?.let { process(it) }
        }

        // Already awake: a free read of cached scan results, if the user enabled "in range".
        if (transition != Geofence.GEOFENCE_TRANSITION_EXIT) maybeReadInRange(now)
        syncLocked("GEOFENCE", force = false)
    }

    suspend fun onWifiAvailable(intent: Intent) = mutex.withLock {
        val now = engine.now()
        val network = intent.network()
        val connection = wifi.currentConnection(network) ?: WifiNetwork(null, null)
        process(Signal.WifiConnected(connection, at = now, receivedAt = now))
        syncLocked("WIFI", force = false)
    }

    /** Process one signal and tell the user when the stored arrival changed. */
    private suspend fun process(signal: Signal): SignalResult {
        val result = processSignal(signal)
        val arrival = result.arrivalChanged
        val day = result.day
        if (arrival != null && day != null) {
            notifier.arrival(day.date, arrival.at, day.zoneId ?: engine.zone, arrival.source, arrival.estimated, arrival.replacesLaterRecord)
        }
        return result
    }

    private suspend fun maybeReadInRange(now: Instant) {
        val app = settingsRepo.current()
        if (!app.detection.wifiInRangeCountsAsPresent || isPowerSave()) return
        if (!deviceStatus.granted(Manifest.permission.ACCESS_FINE_LOCATION)) return
        val networks = wifi.cachedScanResults().map { WifiNetwork(it.ssid, it.bssid) }
        if (networks.isNotEmpty()) process(Signal.WifiInRange(networks, at = now, receivedAt = now))
    }

    private suspend fun syncLocked(reason: String, force: Boolean) {
        val now = engine.now()
        val today = engine.today()
        system(now, "WAKE:$reason${if (force) ":FORCE" else ""}")

        // Commit exits whose debounce elapsed (yesterday too, if the evening disarm was missed).
        settleDay(today.minusDays(1))
        settleDay(today)

        val app = settingsRepo.current()
        var ctx = loader.load(today)

        // Missed-entry check, once per day, when due: "No arrival detected today".
        val timeline = engine.timelineFor(today, ctx.schedule)
        var state = arming.get()
        if (app.setupCompleted && timeline != null && !now.isBefore(timeline.missedEntryCheckAt) &&
            state.missedEntryCheckDate != today && ctx.day?.arrivalAt == null
        ) {
            val decision = checkMissedEntry(today)
            if (decision is MissedEntryDecision.Notify) {
                notifier.missedEntry(today, today, timeline, decision.suggestedArrival, engine.zone, repeat = false)
            }
            state = arming.update {
                it.copy(missedEntryCheckDate = today, missedNotifiedDate = if (decision is MissedEntryDecision.Notify) today else it.missedNotifiedDate)
            }
            ctx = loader.load(today)
        }

        // Resolved (arrival logged or day marked off): the reminder is no longer needed.
        val resolved = ctx.day?.arrivalAt != null || (ctx.day?.status ?: DayStatus.WORKING) != DayStatus.WORKING
        val renotifyPending = state.missedNotifiedDate == today && state.missedRenotifyDate != today && !resolved
        if (state.missedNotifiedDate == today && resolved) notifier.cancelMissed()
        if (renotifyPending && timeline != null && !now.isBefore(timeline.missedEntryRenotifyAt)) {
            // Re-notify once after 60 min if still ignored (spec section 2).
            val again = engine.onEntryWindowEnd(ctx, now)
            if (again is MissedEntryDecision.Notify) {
                notifier.missedEntry(today, today, timeline, again.suggestedArrival, engine.zone, repeat = true)
                system(now, "MISSED_ENTRY_RENOTIFY")
            }
            state = arming.update { it.copy(missedRenotifyDate = today) }
        }

        val plan = if (app.setupCompleted) listenerPlanner.plan(ctx, now) else idlePlan()
        reconcile(plan, ctx, state, now, force)

        val wake = if (app.setupCompleted) {
            wakePlanner.next(
                today,
                ctx.schedule,
                ctx.settings,
                ctx.workplaces,
                ctx.day,
                missedEntryCheckDone = state.missedEntryCheckDate == today,
                now = now,
                renotifyPending = state.missedNotifiedDate == today && state.missedRenotifyDate != today && !resolved,
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
