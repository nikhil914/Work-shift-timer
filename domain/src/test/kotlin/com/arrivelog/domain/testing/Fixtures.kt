package com.arrivelog.domain.testing

import com.arrivelog.domain.engine.ArrivalDecisionEngine
import com.arrivelog.domain.engine.Decision
import com.arrivelog.domain.engine.DecisionContext
import com.arrivelog.domain.engine.ListenerPlan
import com.arrivelog.domain.engine.ListenerPlanner
import com.arrivelog.domain.engine.MissedEntryDecision
import com.arrivelog.domain.engine.Outcome
import com.arrivelog.domain.model.DayStatus
import com.arrivelog.domain.model.DetectionMode
import com.arrivelog.domain.model.DetectionSettings
import com.arrivelog.domain.model.DetectionState
import com.arrivelog.domain.model.DetectionTuning
import com.arrivelog.domain.model.ScheduleSettings
import com.arrivelog.domain.model.Signal
import com.arrivelog.domain.model.WifiNetwork
import com.arrivelog.domain.model.WorkDay
import com.arrivelog.domain.model.Workplace
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

val DUBAI: ZoneId = ZoneId.of("Asia/Dubai")

// Week of 26 Oct 2026: Monday 26 … Sunday 1 Nov.
val MON: LocalDate = LocalDate.of(2026, 10, 26)
val TUE: LocalDate = MON.plusDays(1)
val THU: LocalDate = MON.plusDays(3)
val FRI: LocalDate = MON.plusDays(4)
val SAT: LocalDate = MON.plusDays(5)
val SUN: LocalDate = MON.plusDays(6)

const val OFFICE_ID = 1L
const val SITE2_ID = 2L
const val OFFICE_SSID = "CorpWiFi"
const val OFFICE_BSSID = "aa:bb:cc:dd:ee:01"

val OFFICE = Workplace(
    id = OFFICE_ID,
    name = "HQ",
    latitude = 24.4539,
    longitude = 54.3773,
    radiusMeters = 200,
    ssids = setOf(OFFICE_SSID),
    bssids = setOf(OFFICE_BSSID),
)
val SITE2 = Workplace(id = SITE2_ID, name = "Site 2", latitude = 24.49, longitude = 54.36, ssids = setOf("Site2-WiFi"))

val officeWifi = WifiNetwork("\"$OFFICE_SSID\"", OFFICE_BSSID)
val homeWifi = WifiNetwork("\"HomeNet\"", "11:22:33:44:55:66")

fun LocalDate.at(hour: Int, minute: Int, zone: ZoneId = DUBAI): Instant =
    ZonedDateTime.of(this, LocalTime.of(hour, minute), zone).toInstant()

class MutableClock(var now: Instant, private val zoneId: ZoneId = DUBAI) : Clock() {
    override fun getZone(): ZoneId = zoneId
    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)
    override fun instant(): Instant = now
}

/**
 * Drives the engine the way ProcessSignalUseCase does, with an in-memory "database" that applies
 * outcomes blindly. (The real SQL guards are covered separately by the DAO tests.)
 */
class EngineHarness(
    var settings: DetectionSettings = DetectionSettings(),
    var schedule: ScheduleSettings = ScheduleSettings(),
    var workplaces: List<Workplace> = listOf(OFFICE),
    zone: ZoneId = DUBAI,
    val tuning: DetectionTuning = DetectionTuning(),
) {
    val clock = MutableClock(MON.at(6, 0, zone), zone)
    val engine = ArrivalDecisionEngine(clock, tuning)
    val planner = ListenerPlanner(clock, tuning)
    val days = mutableMapOf<LocalDate, WorkDay>()
    val states = mutableMapOf<LocalDate, DetectionState>()

    fun mode(mode: DetectionMode) = apply { settings = settings.copy(mode = mode) }

    fun ctx(date: LocalDate) = DecisionContext(
        date = date,
        settings = settings,
        schedule = schedule,
        workplaces = workplaces,
        day = days[date],
        state = states[date] ?: DetectionState(date),
    )

    fun send(signal: Signal): Decision {
        clock.now = maxOf(clock.now, signal.receivedAt)
        val date = engine.localDateOf(signal.at)
        val decision = engine.onSignal(signal, ctx(date))
        apply(date, decision)
        return decision
    }

    fun settle(date: LocalDate, now: Instant): Decision {
        clock.now = now
        val decision = engine.settle(ctx(date))
        apply(date, decision)
        return decision
    }

    fun missedEntry(date: LocalDate, now: Instant): MissedEntryDecision {
        clock.now = now
        return engine.onEntryWindowEnd(ctx(date))
    }

    fun plan(date: LocalDate, now: Instant): ListenerPlan {
        clock.now = now
        return planner.plan(ctx(date))
    }

    fun day(date: LocalDate): WorkDay? = days[date]

    private fun apply(date: LocalDate, decision: Decision) {
        var day = days[date] ?: WorkDay(date)
        for (outcome in decision.outcomes) {
            day = when (outcome) {
                is Outcome.ArrivalRecorded -> day.copy(
                    arrivalAt = outcome.at,
                    arrivalSource = outcome.source,
                    arrivalWorkplaceId = outcome.workplaceId,
                    status = DayStatus.WORKING,
                    originalArrivalAt = outcome.at,
                )
                is Outcome.ExitRecorded -> day.copy(exitAt = outcome.at, exitSource = outcome.source)
                is Outcome.ExitReopened -> day.copy(exitAt = null, exitSource = null)
                is Outcome.Noted, is Outcome.Ignored -> day
            }
        }
        if (decision.outcomes.any { it is Outcome.ArrivalRecorded || it is Outcome.ExitRecorded || it is Outcome.ExitReopened }) {
            days[date] = day
        }
        states[date] = decision.newState
    }

    // Signal builders ---------------------------------------------------------------------------

    fun enter(at: Instant, workplaceId: Long = OFFICE_ID, receivedAt: Instant = at) =
        send(Signal.GeofenceEnter(workplaceId, at, receivedAt, accuracyM = 25f))

    fun dwell(at: Instant, workplaceId: Long = OFFICE_ID, receivedAt: Instant = at) =
        send(Signal.GeofenceDwell(workplaceId, at, receivedAt, accuracyM = 25f))

    /** ENTER followed by the DWELL the loitering delay later. */
    fun arriveByGeofence(at: Instant, workplaceId: Long = OFFICE_ID): Decision {
        enter(at, workplaceId)
        return dwell(at.plus(tuning.loiteringDelay), workplaceId)
    }

    fun exit(at: Instant, workplaceId: Long = OFFICE_ID, connected: WifiNetwork? = null, receivedAt: Instant = at) =
        send(Signal.GeofenceExit(workplaceId, at, receivedAt, accuracyM = 25f, connectedWifi = connected))

    fun wifi(at: Instant, network: WifiNetwork = officeWifi, receivedAt: Instant = at) =
        send(Signal.WifiConnected(network, at, receivedAt))

    fun wifiLost(at: Instant, network: WifiNetwork? = officeWifi) = send(Signal.WifiLost(network, at))

    fun inRange(at: Instant, vararg networks: WifiNetwork) = send(Signal.WifiInRange(networks.toList(), at))

    fun manual(at: Instant) = send(Signal.Manual(at))
}
