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
import com.arrivelog.domain.testing.MON
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

    @Test
    fun `left now fills an empty exit once and blocks later auto exits`() = runTest {
        process(Signal.WifiConnected(officeWifi, at = MON.at(8, 7)))
        val logExit = LogExitUseCase(engine, days, log, tx)
        clock.now = MON.at(19, 0)
        assertThat(logExit(MON)).isTrue()
        assertThat(days.rows[MON]?.exitAt).isEqualTo(MON.at(19, 0))
        assertThat(days.rows[MON]?.exitSource).isEqualTo(SignalSource.MANUAL)
        // Second tap (or a stale notification) does nothing.
        assertThat(logExit(MON, MON.at(19, 30))).isFalse()
        assertThat(days.rows[MON]?.exitAt).isEqualTo(MON.at(19, 0))
    }

    @Test
    fun `left now needs an arrival before it`() = runTest {
        val logExit = LogExitUseCase(engine, days, log, tx)
        assertThat(logExit(MON, MON.at(19, 0))).isFalse()
        process(Signal.WifiConnected(officeWifi, at = MON.at(8, 7)))
        assertThat(logExit(MON, MON.at(8, 0))).isFalse()
    }

    // Fakes ------------------------------------------------------------------------------------

    private class MutexTx : TransactionRunner {
        private val mutex = Mutex()
        override suspend fun <T> inTransaction(block: suspend () -> T): T = mutex.withLock { block() }
    }

    private class FakeSettings : SettingsRepository {
        private val state = MutableStateFlow(AppSettings(setupCompleted = true))
        override val settings: Flow<AppSettings> = state
        override suspend fun current(): AppSettings = state.value
        override suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings =
            transform(state.value).also { state.value = it }
    }

    private class FakeWorkplaces(private val all: List<Workplace>) : WorkplaceRepository {
        override fun observeAll(): Flow<List<Workplace>> = flowOf(all)
        override suspend fun getAll(): List<Workplace> = all
        override suspend fun upsert(workplace: Workplace): Long = workplace.id
        override suspend fun delete(id: Long) = Unit
    }

    /** Mirrors the SQL guards of WorkDayDao closely enough for use-case tests. */
    private class FakeWorkDays : WorkDayRepository {
        val rows = mutableMapOf<LocalDate, WorkDay>()
        var arrivalWrites = 0
        private val flow = MutableStateFlow(0)

        override fun observe(date: LocalDate): Flow<WorkDay?> = flow.map { rows[date] }
        override fun observeRange(from: LocalDate, to: LocalDate): Flow<List<WorkDay>> =
            flow.map { rows.values.filter { it.date in from..to } }

        override suspend fun get(date: LocalDate): WorkDay? {
            yield() // give the other coroutine a chance to interleave, as a real DB would
            return rows[date]
        }

        override suspend fun recordArrival(date: LocalDate, at: Instant, source: SignalSource, workplaceId: Long?, zone: ZoneId): Boolean {
            val row = rows[date] ?: WorkDay(date)
            val current = row.arrivalAt
            if (current != null && !(at.isBefore(current) && row.arrivalIsAutoAndUntouched)) return false
            rows[date] = row.copy(arrivalAt = at, arrivalSource = source, arrivalWorkplaceId = workplaceId, zoneId = zone, status = DayStatus.WORKING)
            arrivalWrites++
            return true
        }

        override suspend fun recordExit(date: LocalDate, at: Instant, source: SignalSource): Boolean {
            val row = rows[date] ?: return false
            rows[date] = row.copy(exitAt = at, exitSource = source)
            return true
        }

        override suspend fun reopenExit(date: LocalDate, reentryAt: Instant): Boolean {
            val row = rows[date] ?: return false
            rows[date] = row.copy(exitAt = null, exitSource = null)
            return true
        }

        override suspend fun markStatus(date: LocalDate, status: DayStatus): Boolean {
            rows[date] = (rows[date] ?: WorkDay(date)).copy(status = status)
            return true
        }

        override suspend fun delete(date: LocalDate) {
            rows.remove(date)
        }

        override suspend fun save(day: WorkDay) {
            rows[day.date] = day
        }
    }

    private class FakeStates : DetectionStateRepository {
        val rows = mutableMapOf<LocalDate, DetectionState>()
        override fun observe(date: LocalDate): Flow<DetectionState?> = flowOf(rows[date])
        override suspend fun get(date: LocalDate): DetectionState? = rows[date]
        override suspend fun put(state: DetectionState) {
            rows[state.date] = state
        }
        override suspend fun delete(date: LocalDate) {
            rows.remove(date)
        }
        override suspend fun deleteBefore(date: LocalDate) {
            rows.keys.removeAll { it < date }
        }
    }

    private class FakeLog : EventLogRepository {
        val events = mutableListOf<RawEvent>()
        override fun observeRecent(limit: Int): Flow<List<RawEvent>> = flowOf(events.takeLast(limit))
        override suspend fun append(event: RawEvent): Long {
            events += event
            return events.size.toLong()
        }
        override suspend fun between(from: Instant, to: Instant): List<RawEvent> = events.filter { it.at in from..to }
        override suspend fun countReceivedBetween(from: Instant, to: Instant): Int = events.count { it.receivedAt in from..to }
        override suspend fun deleteOlderThan(cutoff: Instant): Int = 0
    }
}
