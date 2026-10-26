package com.arrivelog.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arrivelog.data.db.ArriveLogDatabase
import com.arrivelog.data.repository.RoomDetectionStateRepository
import com.arrivelog.data.repository.RoomEventLogRepository
import com.arrivelog.data.repository.RoomTransactionRunner
import com.arrivelog.data.repository.RoomWorkDayRepository
import com.arrivelog.data.repository.RoomWorkplaceRepository
import com.arrivelog.domain.engine.ArrivalDecisionEngine
import com.arrivelog.domain.engine.ListenerPlanner
import com.arrivelog.domain.model.AppSettings
import com.arrivelog.domain.model.DetectionTuning
import com.arrivelog.domain.model.Signal
import com.arrivelog.domain.model.WifiNetwork
import com.arrivelog.domain.model.Workplace
import com.arrivelog.domain.repository.SettingsRepository
import com.arrivelog.domain.usecase.DayContextLoader
import com.arrivelog.domain.usecase.ProcessSignalUseCase
import com.arrivelog.domain.usecase.SettleDayUseCase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Engine + use cases + real Room: the path every background receiver will take in Phase 3. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DetectionPipelineTest {

    private val dubai = ZoneId.of("Asia/Dubai")
    private val monday = LocalDate.of(2026, 10, 26)
    private fun at(hhmm: String): Instant = monday.atTime(java.time.LocalTime.parse(hhmm)).atZone(dubai).toInstant()

    private lateinit var db: ArriveLogDatabase
    private lateinit var process: ProcessSignalUseCase
    private lateinit var settle: SettleDayUseCase
    private lateinit var workDays: RoomWorkDayRepository
    private lateinit var events: RoomEventLogRepository
    private var clock: Clock = Clock.fixed(Instant.parse("2026-10-26T04:30:00Z"), ZoneId.of("Asia/Dubai"))

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ArriveLogDatabase::class.java)
            .build() // real query/transaction executors, no main-thread shortcut
        val tuning = DetectionTuning()
        val clockRef = object : Clock() {
            override fun getZone(): ZoneId = clock.zone
            override fun withZone(zone: ZoneId): Clock = clock.withZone(zone)
            override fun instant(): Instant = clock.instant()
        }
        val engine = ArrivalDecisionEngine(clockRef, tuning)
        workDays = RoomWorkDayRepository(db.workDayDao(), clockRef)
        val states = RoomDetectionStateRepository(db.detectionStateDao())
        events = RoomEventLogRepository(db.rawEventDao())
        val workplaces = RoomWorkplaceRepository(db.workplaceDao())
        workplaces.upsert(Workplace(id = 1, name = "HQ", latitude = 24.45, longitude = 54.37, ssids = setOf("CorpWiFi")))
        val tx = RoomTransactionRunner(db)
        val loader = DayContextLoader(FixedSettings(), workplaces, workDays, states)
        process = ProcessSignalUseCase(engine, ListenerPlanner(clockRef, tuning), loader, workDays, states, events, tx)
        settle = SettleDayUseCase(engine, loader, workDays, states, events, tx)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun simultaneousGeofenceAndWifi_logExactlyOneArrival() = runBlocking {
        val wifi = Signal.WifiConnected(WifiNetwork("\"CorpWiFi\"", null), at("08:07"))
        val dwell = Signal.GeofenceDwell(1, at("08:09"))
        val results = (1..10).flatMap { listOf(wifi, dwell) }.map { signal ->
            async(Dispatchers.IO) { process(signal) }
        }.awaitAll()

        assertThat(results.count { it.arrivalChanged != null }).isEqualTo(1)
        assertThat(workDays.get(monday)?.arrivalAt).isEqualTo(at("08:07"))
        assertThat(events.between(at("00:00"), at("23:59"))).hasSize(20)
    }

    @Test
    fun exitIsDebouncedThenCommittedBySettle() = runBlocking {
        process(Signal.WifiConnected(WifiNetwork("CorpWiFi", null), at("08:00")))
        process(Signal.GeofenceExit(1, at("17:02")))
        assertThat(workDays.get(monday)?.exitAt).isNull()

        clock = Clock.fixed(at("17:20"), dubai)
        val decision = settle(monday)
        assertThat(decision.exit?.at).isEqualTo(at("17:02"))
        assertThat(workDays.get(monday)?.exitAt).isEqualTo(at("17:02"))
        assertThat(events.between(at("17:00"), at("17:30")).last().reason).startsWith("SETTLE:EXIT_RECORDED")
    }

    private class FixedSettings : SettingsRepository {
        private val state = MutableStateFlow(AppSettings(setupCompleted = true))
        override val settings: Flow<AppSettings> = state
        override suspend fun current(): AppSettings = state.value
        override suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings =
            transform(state.value).also { state.value = it }
    }
}
