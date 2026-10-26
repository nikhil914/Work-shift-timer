package com.arrivelog.ui.home

import com.arrivelog.background.DetectionActions
import com.arrivelog.domain.engine.ArrivalDecisionEngine
import com.arrivelog.domain.home.DayBadge
import com.arrivelog.domain.home.DaySummarizer
import com.arrivelog.domain.model.AppSettings
import com.arrivelog.domain.model.DayStatus
import com.arrivelog.domain.model.DetectionState
import com.arrivelog.domain.model.DetectionTuning
import com.arrivelog.domain.model.RawEvent
import com.arrivelog.domain.model.SignalSource
import com.arrivelog.domain.model.WorkDay
import com.arrivelog.domain.repository.DetectionStateRepository
import com.arrivelog.domain.repository.EventLogRepository
import com.arrivelog.domain.repository.SettingsRepository
import com.arrivelog.domain.repository.TransactionRunner
import com.arrivelog.domain.repository.WorkDayRepository
import com.arrivelog.domain.usecase.DayEdit
import com.arrivelog.domain.usecase.EditDayUseCase
import com.arrivelog.domain.usecase.EditResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Spec section 9: the home card state. Plain JVM, fakes for storage and the background layer. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val dubai = ZoneId.of("Asia/Dubai")
    private val monday = LocalDate.of(2026, 10, 26)
    private fun at(date: LocalDate, hhmm: String): Instant = ZonedDateTime.of(date, LocalTime.parse(hhmm), dubai).toInstant()

    private val clock = MutableClock(at(monday, "09:00"), dubai)
    private val tuning = DetectionTuning()
    private val dispatcher = StandardTestDispatcher()
    private val days = FakeWorkDays()
    private val actions = FakeActions()

    private lateinit var vm: HomeViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val engine = ArrivalDecisionEngine(clock, tuning)
        vm = HomeViewModel(
            settings = FakeSettings(),
            workDays = days,
            summarizer = DaySummarizer(clock, tuning),
            editDay = EditDayUseCase(engine, days, FakeStates(), FakeLog(), DirectTx()),
            actions = actions,
            clock = clock,
        )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.collectState() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        runCurrent()
    }

    @Test
    fun `before arrival the card says not yet`() = runTest(dispatcher) {
        collectState()
        val today = vm.state.value.today
        assertThat(vm.state.value.loading).isFalse()
        assertThat(today?.date).isEqualTo(monday)
        assertThat(today?.badge).isEqualTo(DayBadge.NotYet)
        assertThat(today?.day).isNull()
    }

    @Test
    fun `an arrival shows lateness and a live counter that ticks`() = runTest(dispatcher) {
        days.put(WorkDay(monday, arrivalAt = at(monday, "08:12"), arrivalSource = SignalSource.WIFI, zoneId = dubai))
        collectState()
        assertThat(vm.state.value.today?.badge).isEqualTo(DayBadge.Late(12))
        assertThat(vm.state.value.today?.atWorkNow).isTrue()
        assertThat(vm.state.value.today?.worked?.toMinutes()).isEqualTo(48)

        clock.now = at(monday, "09:30")
        advanceTimeBy(31_000)
        runCurrent()
        assertThat(vm.state.value.today?.worked?.toMinutes()).isEqualTo(78)
    }

    @Test
    fun `the log starts at the first recorded day and flags unresolved working days`() = runTest(dispatcher) {
        // Thursday last week resolved (WFH), Friday last week left blank.
        days.put(WorkDay(monday.minusDays(4), status = DayStatus.WFH))
        collectState()
        val state = vm.state.value
        val dates = state.weeks.flatMap { w -> w.days.map { it.date } }
        assertThat(dates.first()).isEqualTo(monday)
        assertThat(dates.last()).isEqualTo(monday.minusDays(4))
        // Friday 23 Oct had no arrival and no status; the weekend is off.
        assertThat(state.attentionCount).isEqualTo(1)
        assertThat(state.canShowOlder).isFalse()
    }

    @Test
    fun `saving an edit replans the background`() = runTest(dispatcher) {
        collectState()
        var result: EditResult? = null
        vm.save(monday, DayEdit(LocalTime.of(8, 0), null, DayStatus.WORKING, null)) { result = it }
        runCurrent()
        assertThat(result).isInstanceOf(EditResult.Saved::class.java)
        assertThat(actions.edits).containsExactly(monday)
        assertThat(vm.state.value.today?.badge).isEqualTo(DayBadge.OnTime)
        assertThat(vm.state.value.today?.day?.arrivalSource).isEqualTo(SignalSource.MANUAL)
    }

    @Test
    fun `log now goes through the background layer`() = runTest(dispatcher) {
        vm.logNow()
        runCurrent()
        assertThat(actions.logNowCalls).isEqualTo(1)
    }

    // Fakes ------------------------------------------------------------------------------------

    private class MutableClock(var now: Instant, private val zoneId: ZoneId) : Clock() {
        override fun getZone(): ZoneId = zoneId
        override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)
        override fun instant(): Instant = now
    }

    private class FakeActions : DetectionActions {
        var logNowCalls = 0
        val edits = mutableListOf<LocalDate>()
        override suspend fun logNow(): Boolean {
            logNowCalls++
            return true
        }
        override suspend fun afterUserEdit(date: LocalDate) {
            edits += date
        }
    }

    private class FakeSettings : SettingsRepository {
        private val state = MutableStateFlow(AppSettings(setupCompleted = true))
        override val settings: Flow<AppSettings> = state
        override suspend fun current(): AppSettings = state.value
        override suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings = transform(state.value).also { state.value = it }
    }

    private class FakeWorkDays : WorkDayRepository {
        private val rows = MutableStateFlow<Map<LocalDate, WorkDay>>(emptyMap())
        override fun observe(date: LocalDate): Flow<WorkDay?> = rows.map { it[date] }
        override fun observeRange(from: LocalDate, to: LocalDate): Flow<List<WorkDay>> =
            rows.map { all -> all.values.filter { it.date in from..to }.sortedByDescending { it.date } }
        override fun observeEarliestDate(): Flow<LocalDate?> = rows.map { it.keys.minOrNull() }
        override suspend fun get(date: LocalDate): WorkDay? = rows.value[date]
        override suspend fun recordArrival(date: LocalDate, at: Instant, source: SignalSource, workplaceId: Long?, zone: ZoneId) = false
        override suspend fun recordExit(date: LocalDate, at: Instant, source: SignalSource) = false
        override suspend fun reopenExit(date: LocalDate, reentryAt: Instant) = false
        override suspend fun markStatus(date: LocalDate, status: DayStatus) = false
        override suspend fun put(day: WorkDay) {
            rows.value = rows.value + (day.date to day)
        }
        override suspend fun delete(date: LocalDate) {
            rows.value = rows.value - date
        }
    }

    private class FakeStates : DetectionStateRepository {
        override fun observe(date: LocalDate): Flow<DetectionState?> = flowOf(null)
        override suspend fun get(date: LocalDate): DetectionState? = null
        override suspend fun put(state: DetectionState) = Unit
        override suspend fun delete(date: LocalDate) = Unit
        override suspend fun deleteBefore(date: LocalDate) = Unit
    }

    private class FakeLog : EventLogRepository {
        override fun observeRecent(limit: Int): Flow<List<RawEvent>> = flowOf(emptyList())
        override suspend fun append(event: RawEvent): Long = 0
        override suspend fun between(from: Instant, to: Instant): List<RawEvent> = emptyList()
        override suspend fun countReceivedBetween(from: Instant, to: Instant): Int = 0
        override suspend fun deleteOlderThan(cutoff: Instant): Int = 0
    }

    private class DirectTx : TransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }
}
