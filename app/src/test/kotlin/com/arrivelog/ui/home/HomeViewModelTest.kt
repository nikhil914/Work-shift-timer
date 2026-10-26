package com.arrivelog.ui.home

import com.arrivelog.background.ReplanTrigger
import com.arrivelog.domain.day.DayChip
import com.arrivelog.domain.day.DayEdit
import com.arrivelog.domain.engine.ArrivalDecisionEngine
import com.arrivelog.domain.model.AppSettings
import com.arrivelog.domain.model.DayStatus
import com.arrivelog.domain.model.DetectionState
import com.arrivelog.domain.model.DetectionTuning
import com.arrivelog.domain.model.PendingExit
import com.arrivelog.domain.model.RawEvent
import com.arrivelog.domain.model.SignalSource
import com.arrivelog.domain.model.WorkDay
import com.arrivelog.domain.repository.DetectionStateRepository
import com.arrivelog.domain.repository.EventLogRepository
import com.arrivelog.domain.repository.SettingsRepository
import com.arrivelog.domain.repository.TransactionRunner
import com.arrivelog.domain.repository.WorkDayRepository
import com.arrivelog.domain.usecase.EditDayUseCase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Spec section 9: the home card state, driven by fakes and a fixed clock (Mon 26 Oct 2026, Dubai). */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val dubai = ZoneId.of("Asia/Dubai")
    private val monday = LocalDate.of(2026, 10, 26)
    private fun at(date: LocalDate, h: Int, m: Int): Instant = ZonedDateTime.of(date, LocalTime.of(h, m), dubai).toInstant()

    private val days = FakeWorkDays()
    private val states = FakeStates()
    private val replan = FakeReplan()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(now: Instant): HomeViewModel {
        val clock = Clock.fixed(now, dubai)
        val engine = ArrivalDecisionEngine(clock, DetectionTuning())
        val edit = EditDayUseCase(engine, days, states, FakeLog(), DirectTx())
        return HomeViewModel(FakeSettings(), days, states, edit, replan, clock)
    }

    @Test
    fun `before the missed-entry check with nothing logged the card says not yet`() = runTest {
        val vm = viewModel(at(monday, 7, 45))
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        val today = vm.state.value.today
        assertThat(today?.chip).isEqualTo(DayChip.NotYet)
        assertThat(today?.arrivalAt).isNull()
    }

    @Test
    fun `late arrival shows minutes late, source and a live worked duration`() = runTest {
        days.rows[monday] = WorkDay(monday, arrivalAt = at(monday, 8, 12), arrivalSource = SignalSource.WIFI, zoneId = dubai)
        val vm = viewModel(at(monday, 9, 0))
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        val today = vm.state.value.today
        assertThat(today?.chip).isEqualTo(DayChip.Late(12))
        assertThat(today?.arrivalSource).isEqualTo(SignalSource.WIFI)
        assertThat(today?.worked).isEqualTo(Duration.ofMinutes(48))
    }

    @Test
    fun `pending exit is surfaced for the leaving state`() = runTest {
        days.rows[monday] = WorkDay(monday, arrivalAt = at(monday, 7, 55), arrivalSource = SignalSource.GEOFENCE)
        states.rows[monday] = DetectionState(monday, pendingExit = PendingExit(at(monday, 16, 50), SignalSource.GEOFENCE))
        val vm = viewModel(at(monday, 16, 55))
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        assertThat(vm.state.value.today?.chip).isEqualTo(DayChip.OnTime)
        assertThat(vm.state.value.pendingExitAt).isEqualTo(at(monday, 16, 50))
    }

    @Test
    fun `weekend card and an unresolved past working day needing attention`() = runTest {
        val saturday = monday.plusDays(5)
        val vm = viewModel(at(saturday, 10, 0))
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        val s = vm.state.value
        assertThat(s.today?.chip).isEqualTo(DayChip.Status(DayStatus.WEEKEND))
        // Mon–Fri of that week had no arrival and no status: all five need attention.
        assertThat(s.needsAttention.map { it.date }).containsAtLeast(monday, monday.plusDays(4))
        assertThat(s.weeks.first().weekStart).isEqualTo(monday)
    }

    @Test
    fun `saving a valid edit re-plans, an invalid one reports the problem`() = runTest {
        val vm = viewModel(at(monday, 12, 0))
        vm.save(monday, DayEdit(arrivalAt = at(monday, 8, 0), exitAt = at(monday, 7, 0), status = DayStatus.WORKING, note = null))
        runCurrent()
        assertThat(vm.editError.value).isNotNull()
        assertThat(replan.dates).isEmpty()

        vm.save(monday, DayEdit(arrivalAt = at(monday, 8, 0), exitAt = null, status = DayStatus.WORKING, note = "badge reader down"))
        runCurrent()
        assertThat(vm.editError.value).isNull()
        assertThat(replan.dates).containsExactly(monday)
        assertThat(days.rows[monday]?.arrivalSource).isEqualTo(SignalSource.MANUAL)
        assertThat(days.rows[monday]?.note).isEqualTo("badge reader down")
    }

    // Fakes ------------------------------------------------------------------------------------

    private class FakeReplan : ReplanTrigger {
        val dates = mutableListOf<LocalDate>()
        override suspend fun afterEdit(date: LocalDate) {
            dates += date
        }
    }

    private class DirectTx : TransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }

    private class FakeSettings : SettingsRepository {
        private val s = MutableStateFlow(AppSettings(setupCompleted = true))
        override val settings: Flow<AppSettings> = s
        override suspend fun current(): AppSettings = s.value
        override suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings = transform(s.value).also { s.value = it }
    }

    private class FakeWorkDays : WorkDayRepository {
        val rows = mutableMapOf<LocalDate, WorkDay>()
        private val tick = MutableStateFlow(0)
        private fun changed() {
            tick.value++
        }

        override fun observe(date: LocalDate): Flow<WorkDay?> = tick.map { rows[date] }
        override fun observeRange(from: LocalDate, to: LocalDate): Flow<List<WorkDay>> =
            tick.map { rows.values.filter { it.date in from..to }.sortedByDescending { it.date } }

        override suspend fun get(date: LocalDate): WorkDay? = rows[date]
        override suspend fun recordArrival(date: LocalDate, at: Instant, source: SignalSource, workplaceId: Long?, zone: ZoneId) = false
        override suspend fun recordExit(date: LocalDate, at: Instant, source: SignalSource) = false
        override suspend fun reopenExit(date: LocalDate, reentryAt: Instant) = false
        override suspend fun markStatus(date: LocalDate, status: DayStatus) = false
        override suspend fun delete(date: LocalDate) {
            rows.remove(date)
            changed()
        }

        override suspend fun save(day: WorkDay) {
            rows[day.date] = day
            changed()
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
        override suspend fun deleteBefore(date: LocalDate) = Unit
    }

    private class FakeLog : EventLogRepository {
        override fun observeRecent(limit: Int): Flow<List<RawEvent>> = flowOf(emptyList())
        override suspend fun append(event: RawEvent): Long = 1
        override suspend fun between(from: Instant, to: Instant): List<RawEvent> = emptyList()
        override suspend fun countReceivedBetween(from: Instant, to: Instant): Int = 0
        override suspend fun deleteOlderThan(cutoff: Instant): Int = 0
    }
}
