package com.arrivelog.domain.usecase

import com.arrivelog.domain.engine.ArrivalDecisionEngine
import com.arrivelog.domain.engine.GeofenceArming
import com.arrivelog.domain.engine.ListenerPlanner
import com.arrivelog.domain.model.AppSettings
import com.arrivelog.domain.model.DayStatus
import com.arrivelog.domain.model.DetectionState
import com.arrivelog.domain.model.DetectionTuning
import com.arrivelog.domain.model.RawEvent
import com.arrivelog.domain.model.RawEventType
import com.arrivelog.domain.model.Signal
import com.arrivelog.domain.model.SignalSource
import com.arrivelog.domain.model.WorkDay
import com.arrivelog.domain.model.Workplace
import com.arrivelog.domain.repository.DetectionStateRepository
import com.arrivelog.domain.repository.EventLogRepository
import com.arrivelog.domain.repository.SettingsRepository
import com.arrivelog.domain.repository.TransactionRunner
import com.arrivelog.domain.repository.WorkDayRepository
import com.arrivelog.domain.repository.WorkplaceRepository
import com.arrivelog.domain.testing.FakeLog
import com.arrivelog.domain.testing.FakeSettings
import com.arrivelog.domain.testing.FakeStates
import com.arrivelog.domain.testing.FakeWorkDays
import com.arrivelog.domain.testing.FakeWorkplaces
import com.arrivelog.domain.testing.MON
import com.arrivelog.domain.testing.MutexTx
import com.arrivelog.domain.testing.MutableClock
import com.arrivelog.domain.testing.OFFICE
import com.arrivelog.domain.testing.OFFICE_ID
import com.arrivelog.domain.testing.at
import com.arrivelog.domain.testing.officeWifi
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ProcessSignalUseCaseTest {

    private val clock = MutableClock(MON.at(8, 0))
    private val tuning = DetectionTuning()
    private val engine = ArrivalDecisionEngine(clock, tuning)
    private val days = FakeWorkDays()
    private val states = FakeStates()
    private val log = FakeLog()
    private val tx = MutexTx()
    private val loader = DayContextLoader(FakeSettings(), FakeWorkplaces(listOf(OFFICE)), days, states)
    private val process = ProcessSignalUseCase(engine, ListenerPlanner(clock, tuning), loader, days, states, log, tx)

    @Test
    fun `arrival is persisted with its raw event and the plan stands geofences down to presence`() = runTest {
        val result = process(Signal.WifiConnected(officeWifi, at = MON.at(8, 7), receivedAt = MON.at(8, 7)))

        assertThat(result.arrivalChanged?.at).isEqualTo(MON.at(8, 7))
        assertThat(days.rows[MON]?.arrivalAt).isEqualTo(MON.at(8, 7))
        assertThat(days.rows[MON]?.zoneId).isEqualTo(ZoneId.of("Asia/Dubai"))
        val raw = log.events.single()
        assertThat(raw.type).isEqualTo(RawEventType.WIFI_CONNECTED)
        assertThat(raw.accepted).isTrue()
        assertThat(raw.reason).isEqualTo("ARRIVAL_RECORDED")
        assertThat(raw.ssid).isEqualTo("\"CorpWiFi\"")
        assertThat(result.plan?.geofence).isEqualTo(GeofenceArming.PRESENCE)
    }

    @Test
    fun `ignored signals are still logged with the reason`() = runTest {
        clock.now = MON.at(5, 0)
        process(Signal.WifiConnected(officeWifi, at = MON.at(5, 0)))
        assertThat(log.events.single().accepted).isFalse()
        assertThat(log.events.single().reason).isEqualTo("BEFORE_WINDOW")
        assertThat(days.rows).isEmpty()
    }

    @Test
    fun `delivery delay is preserved in the raw log`() = runTest {
        clock.now = MON.at(8, 9)
        process(Signal.GeofenceEnter(OFFICE_ID, at = MON.at(8, 3), receivedAt = MON.at(8, 9), accuracyM = 30f))
        val raw = log.events.single()
        assertThat(raw.at).isEqualTo(MON.at(8, 3))
        assertThat(raw.receivedAt).isEqualTo(MON.at(8, 9))
        assertThat(raw.accuracyM).isEqualTo(30f)
    }

    @Test
    fun `geofence and wifi firing together log exactly one arrival`() = runTest {
        val results = listOf(
            async { process(Signal.WifiConnected(officeWifi, at = MON.at(8, 7))) },
            async { process(Signal.GeofenceDwell(OFFICE_ID, at = MON.at(8, 9))) },
        ).awaitAll()
        assertThat(results.count { it.arrivalChanged != null }).isEqualTo(1)
        assertThat(days.arrivalWrites).isEqualTo(1)
        assertThat(log.events).hasSize(2)
    }

    @Test
    fun `a late signal for yesterday returns no plan for today`() = runTest {
        clock.now = MON.plusDays(1).at(0, 5)
        val result = process(Signal.WifiConnected(officeWifi, at = MON.at(10, 0), receivedAt = clock.now))
        assertThat(result.arrivalChanged).isNotNull()
        assertThat(result.plan).isNull()
    }
}
