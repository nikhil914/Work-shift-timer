package com.arrivelog.domain.engine

import com.arrivelog.domain.engine.DecisionReason.AFTER_WINDOW
import com.arrivelog.domain.engine.DecisionReason.ALREADY_ARRIVED
import com.arrivelog.domain.engine.DecisionReason.AWAITING_DWELL
import com.arrivelog.domain.engine.DecisionReason.AWAITING_GEOFENCE
import com.arrivelog.domain.engine.DecisionReason.AWAITING_SIGNAL
import com.arrivelog.domain.engine.DecisionReason.AWAITING_WIFI
import com.arrivelog.domain.engine.DecisionReason.BEFORE_WINDOW
import com.arrivelog.domain.engine.DecisionReason.BSSID_MISMATCH
import com.arrivelog.domain.engine.DecisionReason.DAY_MARKED_NOT_WORKING
import com.arrivelog.domain.engine.DecisionReason.EVIDENCE_UNDONE
import com.arrivelog.domain.engine.DecisionReason.EXIT_BEFORE_ARRIVAL
import com.arrivelog.domain.engine.DecisionReason.EXIT_CANCELLED_REENTRY
import com.arrivelog.domain.engine.DecisionReason.EXIT_CONTRADICTED_BY_WIFI
import com.arrivelog.domain.engine.DecisionReason.EXIT_EDITED_MANUALLY
import com.arrivelog.domain.engine.DecisionReason.EXIT_NOT_LATER
import com.arrivelog.domain.engine.DecisionReason.EXIT_PENDING
import com.arrivelog.domain.engine.DecisionReason.EXIT_TRACKING_OFF
import com.arrivelog.domain.engine.DecisionReason.IN_RANGE_DISABLED
import com.arrivelog.domain.engine.DecisionReason.MODE_EXCLUDES_SIGNAL
import com.arrivelog.domain.engine.DecisionReason.NON_WORKING_DAY
import com.arrivelog.domain.engine.DecisionReason.NO_CHANGE
import com.arrivelog.domain.engine.DecisionReason.NO_WIFI_PRESENCE
import com.arrivelog.domain.engine.DecisionReason.SSID_NOT_OFFICE
import com.arrivelog.domain.engine.DecisionReason.SSID_UNKNOWN
import com.arrivelog.domain.engine.DecisionReason.STALE_EXIT
import com.arrivelog.domain.engine.DecisionReason.UNKNOWN_WORKPLACE
import com.arrivelog.domain.engine.DecisionReason.WORKPLACE_MISMATCH
import com.arrivelog.domain.model.DayStatus
import com.arrivelog.domain.model.DetectionMode
import com.arrivelog.domain.model.DetectionState
import com.arrivelog.domain.model.DetectionTuning
import com.arrivelog.domain.model.GeofenceEvidence
import com.arrivelog.domain.model.PendingExit
import com.arrivelog.domain.model.ScheduleSettings
import com.arrivelog.domain.model.Signal
import com.arrivelog.domain.model.SignalSource
import com.arrivelog.domain.model.WifiEvidence
import com.arrivelog.domain.model.WorkDay
import com.arrivelog.domain.schedule.DayTimeline
import com.arrivelog.domain.schedule.ResolvedDay
import com.arrivelog.domain.schedule.ScheduleResolver
import com.arrivelog.domain.wifi.WifiMatch
import com.arrivelog.domain.wifi.WifiMatcher
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * Pure decision logic: (incoming signal, day context) -> outcomes + new per-day state.
 * No I/O, no Android, no wall-clock reads except through the injected [Clock].
 *
 * Rules, in the order they are applied:
 *  1. Manual signals ("Log now", QS tile) always record an arrival if none exists, any day, any time.
 *  2. Non-working days (weekday OFF, holiday, leave) and days the user marked DAY_OFF/WFH/LEAVE are ignored.
 *  3. The signal must fit the detection mode and match a configured workplace / office SSID.
 *  4. A due pending exit is committed first (debounce measured in event time).
 *  5. Presence signals update evidence; the arrival time is the true time of the earliest
 *     evidence between arm time (window start - 15 min) and window end that satisfies the mode,
 *     so an early arrival is logged at its real time. ENTER alone never counts; DWELL confirms it and
 *     the stored time is the ENTER time.
 *  6. The earliest arrival wins: a later signal never overwrites, but a late-delivered signal that
 *     proves an *earlier* in-window arrival may move an untouched auto arrival earlier.
 *  7. Exits start a debounce; any re-entry before it elapses cancels it. Last exit wins.
 */
class ArrivalDecisionEngine @Inject constructor(
    private val clock: Clock,
    private val tuning: DetectionTuning,
) {
    val zone: ZoneId get() = clock.zone

    fun now(): Instant = clock.instant()

    fun today(): LocalDate = LocalDate.now(clock)

    fun localDateOf(instant: Instant): LocalDate = instant.atZone(clock.zone).toLocalDate()

    fun timelineFor(date: LocalDate, schedule: ScheduleSettings): DayTimeline? =
        when (val resolved = ScheduleResolver.resolve(date, schedule)) {
            is ResolvedDay.Working -> DayTimeline.of(date, resolved.profile, clock.zone, tuning)
            is ResolvedDay.Off -> null
        }

    fun onSignal(signal: Signal, ctx: DecisionContext): Decision = Evaluation(ctx).process(signal)

    /** Commit a pending exit whose debounce has elapsed. Called by the disarm job and when the UI opens. */
    fun settle(ctx: DecisionContext, now: Instant = clock.instant()): Decision = Evaluation(ctx).settleOnly(now)

    fun onEntryWindowEnd(ctx: DecisionContext, now: Instant = clock.instant()): MissedEntryDecision {
        val timeline = timelineFor(ctx.date, ctx.schedule)
            ?: return MissedEntryDecision.NotNeeded(NON_WORKING_DAY)
        val day = ctx.day
        if (day?.arrivalAt != null) return MissedEntryDecision.NotNeeded(ALREADY_ARRIVED)
        if (day != null && day.status != DayStatus.WORKING) return MissedEntryDecision.NotNeeded(DAY_MARKED_NOT_WORKING)
        if (now.isBefore(timeline.missedEntryCheckAt)) return MissedEntryDecision.NotYet(timeline.missedEntryCheckAt)

        val state = ctx.state.takeIf { it.date == ctx.date }
        val floor = state?.ignoreEvidenceBefore
        val suggestion = listOfNotNull(
            state?.pendingEnter?.since,
            state?.geofencePresence?.since,
            state?.wifiPresence?.since,
        ).filter { floor == null || !it.isBefore(floor) }.minOrNull()
        return MissedEntryDecision.Notify(suggestion)
    }

    private inner class Evaluation(private val ctx: DecisionContext) {
        private val initialState = ctx.state.takeIf { it.date == ctx.date } ?: DetectionState(ctx.date)
        private var state = initialState
        private var day: WorkDay = ctx.day ?: WorkDay(ctx.date)
        private val outcomes = mutableListOf<Outcome>()
        private val settings get() = ctx.settings

        fun process(signal: Signal): Decision {
            if (signal is Signal.Manual) {
                if (day.arrivalAt != null) {
                    emit(Outcome.Ignored(ALREADY_ARRIVED))
                } else {
                    recordArrival(Candidate.Found(signal.at, SignalSource.MANUAL, null, estimated = false), replaces = false)
                }
                return result()
            }

            val timeline = timelineFor(ctx.date, ctx.schedule) ?: return reject(NON_WORKING_DAY)
            if (day.arrivalAt == null && day.status != DayStatus.WORKING) return reject(DAY_MARKED_NOT_WORKING)

            val classified = when (val gate = classify(signal)) {
                is Gate.Reject -> return reject(gate.reason)
                is Gate.Pass -> gate.signal
            }
            if (classified is Presence && state.ignoreEvidenceBefore?.isAfter(signal.at) == true) {
                return reject(EVIDENCE_UNDONE)
            }

            settlePendingExit(signal.at)
            when (classified) {
                is Presence -> onPresence(classified, signal.at, timeline)
                is Absence -> onAbsence(classified, signal.at)
            }
            return result()
        }

        fun settleOnly(now: Instant): Decision {
            settlePendingExit(now)
            return result()
        }

        // region classification

        private fun classify(signal: Signal): Gate {
            val mode = settings.mode
            return when (signal) {
                is Signal.Geofence -> when {
                    // Wi-Fi-only mode still uses the geofence for exits (the background Wi-Fi
                    // callback never reports "lost"), but never for the arrival itself.
                    !mode.usesGeofence && !(day.arrivalAt != null && settings.exitTrackingEnabled) ->
                        Gate.Reject(MODE_EXCLUDES_SIGNAL)
                    ctx.workplaces.none { it.id == signal.workplaceId } -> Gate.Reject(UNKNOWN_WORKPLACE)
                    else -> when (signal) {
                        is Signal.GeofenceEnter -> Gate.Pass(GeoEnter(signal.workplaceId))
                        is Signal.GeofenceDwell -> Gate.Pass(GeoDwell(signal.workplaceId))
                        is Signal.GeofenceExit ->
                            if (exitContradictedByWifi(signal)) {
                                Gate.Reject(EXIT_CONTRADICTED_BY_WIFI)
                            } else {
                                Gate.Pass(GeoExit(signal.workplaceId))
                            }
                    }
                }

                is Signal.Wifi -> if (!mode.usesWifi) {
                    Gate.Reject(MODE_EXCLUDES_SIGNAL)
                } else {
                    when (signal) {
                        is Signal.WifiConnected -> wifiGate(
                            WifiMatcher.match(signal.network, ctx.workplaces, settings.matchBssid),
                            inRangeOnly = false,
                        )

                        is Signal.WifiInRange -> if (!settings.wifiInRangeCountsAsPresent) {
                            Gate.Reject(IN_RANGE_DISABLED)
                        } else {
                            wifiGate(
                                WifiMatcher.matchAny(signal.networks, ctx.workplaces, settings.matchBssid),
                                inRangeOnly = true,
                            )
                        }

                        is Signal.WifiLost -> {
                            val match = signal.network?.let { WifiMatcher.match(it, ctx.workplaces, settings.matchBssid) }
                            when {
                                match == WifiMatch.NotOffice || match == WifiMatch.BssidMismatch ->
                                    Gate.Reject(SSID_NOT_OFFICE)
                                state.wifiPresence == null -> Gate.Reject(NO_WIFI_PRESENCE)
                                else -> Gate.Pass(WifiGone)
                            }
                        }
                    }
                }

                is Signal.Manual -> Gate.Reject(NO_CHANGE) // handled before classification
            }
        }

        private fun wifiGate(match: WifiMatch, inRangeOnly: Boolean): Gate = when (match) {
            is WifiMatch.Matched -> Gate.Pass(WifiSeen(match, inRangeOnly))
            WifiMatch.UnknownSsid -> Gate.Reject(SSID_UNKNOWN)
            WifiMatch.NotOffice -> Gate.Reject(SSID_NOT_OFFICE)
            WifiMatch.BssidMismatch -> Gate.Reject(BSSID_MISMATCH)
        }

        /** Still on office Wi-Fi when a geofence EXIT is processed = indoor GPS jitter, not an exit. */
        private fun exitContradictedByWifi(exit: Signal.GeofenceExit): Boolean {
            val network = exit.connectedWifi ?: return false
            return WifiMatcher.match(network, ctx.workplaces, settings.matchBssid) is WifiMatch.Matched
        }

        // endregion

        // region presence / arrival

        private fun onPresence(presence: Presence, at: Instant, timeline: DayTimeline) {
            applyPresence(presence, at)
            val arrivalAt = day.arrivalAt
            if (arrivalAt == null) {
                when (val candidate = candidate(timeline)) {
                    is Candidate.Found -> recordArrival(candidate.withArmEstimate(timeline), replaces = false)
                    is Candidate.None ->
                        emit(if (candidate.outsideWindow) Outcome.Ignored(candidate.reason) else Outcome.Noted(candidate.reason))
                }
                return
            }

            val pendingExit = state.pendingExit
            if (pendingExit != null && at.isAfter(pendingExit.at)) {
                state = state.copy(pendingExit = null)
                emit(Outcome.Noted(EXIT_CANCELLED_REENTRY))
                return
            }

            val exitAt = day.exitAt
            if (exitAt != null && !day.exitEdited && day.exitSource != SignalSource.MANUAL && !at.isBefore(exitAt)) {
                emit(Outcome.ExitReopened(at))
                day = day.copy(exitAt = null, exitSource = null)
                return
            }

            if (day.arrivalIsAutoAndUntouched) {
                val candidate = candidate(timeline)
                if (candidate is Candidate.Found && candidate.at.isBefore(arrivalAt)) {
                    recordArrival(candidate.withArmEstimate(timeline), replaces = true)
                    return
                }
            }
            emit(Outcome.Ignored(ALREADY_ARRIVED))
        }

        private fun applyPresence(presence: Presence, at: Instant) {
            state = when (presence) {
                is GeoEnter -> {
                    // A new ENTER for a fence we think we are inside means its EXIT was lost, so it
                    // starts a fresh entry. Only a duplicate of an older pending ENTER is dropped.
                    val pending = state.pendingEnter
                    if (pending != null && pending.workplaceId == presence.workplaceId && !pending.since.isAfter(at)) {
                        state
                    } else {
                        state.copy(pendingEnter = GeofenceEvidence(presence.workplaceId, at))
                    }
                }

                is GeoDwell -> {
                    val pendingSameFence = state.pendingEnter
                        ?.takeIf { it.workplaceId == presence.workplaceId && !it.since.isAfter(at) }
                    val otherPending = state.pendingEnter?.takeUnless { it.workplaceId == presence.workplaceId }
                    val inside = state.geofencePresence?.takeIf { it.workplaceId == presence.workplaceId }
                    val evidence = pendingSameFence // confirmed entry
                        ?: inside // repeated DWELL (e.g. initial trigger after re-registering): continuous
                        ?: GeofenceEvidence(presence.workplaceId, estimatedEnterTime(at), estimated = true)
                    state.copy(geofencePresence = evidence, pendingEnter = otherPending)
                }

                is WifiSeen -> {
                    // Every "available" callback is a new connection, so the previous one ended even
                    // if we never heard "lost": evidence restarts at this event's time. A cached
                    // in-range snapshot only never downgrades an existing connected evidence.
                    val existing = state.wifiPresence
                    val evidence = if (existing != null && !existing.inRangeOnly && presence.inRangeOnly) {
                        existing
                    } else {
                        WifiEvidence(presence.match.workplaceIds, at, presence.match.ssid, presence.inRangeOnly)
                    }
                    state.copy(wifiPresence = evidence)
                }
            }
            val last = state.lastPresenceAt
            state = state.copy(lastPresenceAt = if (last == null || at.isAfter(last)) at else last)
        }

        /** DWELL without a stored ENTER (e.g. initial trigger, or ENTER lost): back-calculate the entry. */
        private fun estimatedEnterTime(dwellAt: Instant): Instant {
            val estimate = dwellAt.minus(tuning.loiteringDelay)
            val floor = state.ignoreEvidenceBefore
            return if (floor != null && estimate.isBefore(floor)) floor else estimate
        }

        private fun candidate(timeline: DayTimeline): Candidate {
            val geo = state.geofencePresence?.let {
                Candidate.Found(it.since, SignalSource.GEOFENCE, it.workplaceId, it.estimated)
            }
            val wifi = state.wifiPresence?.let {
                Candidate.Found(it.since, SignalSource.WIFI, it.workplaceIds.singleOrNull(), estimated = false)
            }
            val waitingForGeofence = if (state.pendingEnter != null) AWAITING_DWELL else AWAITING_GEOFENCE
            return when (settings.mode) {
                DetectionMode.GEOFENCE_ONLY -> single(geo, timeline, waitingForGeofence)
                DetectionMode.WIFI_ONLY -> single(wifi, timeline, AWAITING_WIFI)
                DetectionMode.GEOFENCE_OR_WIFI -> {
                    val all = listOfNotNull(geo, wifi)
                    if (all.isEmpty()) {
                        Candidate.None(if (state.pendingEnter != null) AWAITING_DWELL else AWAITING_SIGNAL)
                    } else {
                        // Earliest evidence in the accepted range. Older evidence (e.g. a drive-by
                        // whose EXIT was lost) must not poison a real arrival.
                        all.filter { timeline.acceptsArrivalAt(it.at) }.minByOrNull { it.at } ?: outside(all, timeline)
                    }
                }

                DetectionMode.GEOFENCE_AND_WIFI -> andCandidate(geo, wifi, timeline, waitingForGeofence)
            }
        }

        private fun single(found: Candidate.Found?, timeline: DayTimeline, waiting: DecisionReason): Candidate = when {
            found == null -> Candidate.None(waiting)
            timeline.acceptsArrivalAt(found.at) -> found
            else -> outside(listOf(found), timeline)
        }

        private fun andCandidate(
            geo: Candidate.Found?,
            wifi: Candidate.Found?,
            timeline: DayTimeline,
            waitingForGeofence: DecisionReason,
        ): Candidate {
            if (geo == null) return Candidate.None(if (wifi == null && state.pendingEnter == null) AWAITING_SIGNAL else waitingForGeofence)
            if (wifi == null) return Candidate.None(AWAITING_WIFI)
            val wifiWorkplaces = state.wifiPresence?.workplaceIds.orEmpty()
            if (geo.workplaceId !in wifiWorkplaces) return Candidate.None(WORKPLACE_MISMATCH)

            val (earlier, later) = if (!geo.at.isAfter(wifi.at)) geo to wifi else wifi to geo
            // Both conditions first hold at `later`. Within the correlation window the earlier
            // signal is the true arrival; if it is older than that, or predates the window, the
            // arrival is the moment the second condition became true.
            val gap = Duration.between(earlier.at, later.at)
            val chosen = if (gap <= tuning.andCorrelationWindow && timeline.acceptsArrivalAt(earlier.at)) earlier else later
            val result = chosen.copy(workplaceId = geo.workplaceId)
            return if (timeline.acceptsArrivalAt(result.at)) result else outside(listOf(result), timeline)
        }

        private fun outside(candidates: List<Candidate.Found>, timeline: DayTimeline): Candidate.None =
            if (candidates.any { it.at.isBefore(timeline.armAt) }) {
                Candidate.None(BEFORE_WINDOW, outsideWindow = true)
            } else {
                Candidate.None(AFTER_WINDOW, outsideWindow = true)
            }

        /**
         * Evidence stamped right at arm time usually comes from the initial trigger: you were
         * already inside when detection started, so the true arrival was earlier. Flag it.
         */
        private fun Candidate.Found.withArmEstimate(timeline: DayTimeline): Candidate.Found =
            if (!at.isAfter(timeline.armAt.plus(tuning.armSettleMargin))) copy(estimated = true) else this

        private fun recordArrival(candidate: Candidate.Found, replaces: Boolean) {
            emit(
                Outcome.ArrivalRecorded(
                    at = candidate.at,
                    source = candidate.source,
                    workplaceId = candidate.workplaceId,
                    estimated = candidate.estimated,
                    replacesLaterRecord = replaces,
                ),
            )
            day = day.copy(
                arrivalAt = candidate.at,
                arrivalSource = candidate.source,
                arrivalWorkplaceId = candidate.workplaceId,
                status = DayStatus.WORKING,
            )
        }

        // endregion

        // region absence / exit

        private fun onAbsence(absence: Absence, at: Instant) {
            // Out-of-order delivery: a presence event newer than this exit was already processed.
            if (state.lastPresenceAt?.isAfter(at) == true) {
                emit(Outcome.Ignored(STALE_EXIT))
                return
            }
            state = when (absence) {
                is GeoExit -> state.copy(
                    geofencePresence = state.geofencePresence?.takeUnless { it.workplaceId == absence.workplaceId },
                    pendingEnter = state.pendingEnter?.takeUnless { it.workplaceId == absence.workplaceId },
                )
                WifiGone -> state.copy(wifiPresence = null)
            }
            val source = if (absence is GeoExit) SignalSource.GEOFENCE else SignalSource.WIFI
            val arrivalAt = day.arrivalAt
            val exitAt = day.exitAt
            when {
                arrivalAt == null || !at.isAfter(arrivalAt) -> emit(Outcome.Ignored(EXIT_BEFORE_ARRIVAL))
                !settings.exitTrackingEnabled -> emit(Outcome.Ignored(EXIT_TRACKING_OFF))
                day.exitEdited || day.exitSource == SignalSource.MANUAL -> emit(Outcome.Ignored(EXIT_EDITED_MANUALLY))
                exitAt != null && !at.isAfter(exitAt) -> emit(Outcome.Ignored(EXIT_NOT_LATER))
                else -> {
                    val pending = state.pendingExit
                    // Two exit signals for one departure (EXIT + Wi-Fi lost): keep the earlier time.
                    val next = if (pending != null && pending.at.isBefore(at)) pending else PendingExit(at, source)
                    state = state.copy(pendingExit = next)
                    emit(Outcome.Noted(EXIT_PENDING))
                }
            }
        }

        private fun settlePendingExit(now: Instant) {
            val pending = state.pendingExit ?: return
            if (Duration.between(pending.at, now) < tuning.exitDebounce) return
            state = state.copy(pendingExit = null)
            val arrivalAt = day.arrivalAt ?: return
            if (!settings.exitTrackingEnabled || day.exitEdited || day.exitSource == SignalSource.MANUAL) return
            if (!pending.at.isAfter(arrivalAt)) return
            val exitAt = day.exitAt
            if (exitAt != null && !pending.at.isAfter(exitAt)) return
            emit(Outcome.ExitRecorded(pending.at, pending.source))
            day = day.copy(exitAt = pending.at, exitSource = pending.source)
        }

        // endregion

        private fun emit(outcome: Outcome) {
            outcomes += outcome
        }

        private fun reject(reason: DecisionReason): Decision = Decision(listOf(Outcome.Ignored(reason)), initialState)

        private fun result(): Decision = Decision(outcomes.ifEmpty { listOf(Outcome.Noted(NO_CHANGE)) }.toList(), state)
    }

    private sealed interface Classified
    private sealed interface Presence : Classified
    private sealed interface Absence : Classified
    private data class GeoEnter(val workplaceId: Long) : Presence
    private data class GeoDwell(val workplaceId: Long) : Presence
    private data class WifiSeen(val match: WifiMatch.Matched, val inRangeOnly: Boolean) : Presence
    private data class GeoExit(val workplaceId: Long) : Absence
    private data object WifiGone : Absence

    private sealed interface Gate {
        data class Pass(val signal: Classified) : Gate
        data class Reject(val reason: DecisionReason) : Gate
    }

    private sealed interface Candidate {
        data class Found(
            val at: Instant,
            val source: SignalSource,
            val workplaceId: Long?,
            val estimated: Boolean,
        ) : Candidate

        data class None(val reason: DecisionReason, val outsideWindow: Boolean = false) : Candidate
    }
}
