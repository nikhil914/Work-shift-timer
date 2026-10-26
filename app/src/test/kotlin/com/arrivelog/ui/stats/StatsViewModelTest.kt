package com.arrivelog.ui.stats

import com.arrivelog.domain.model.AppSettings
import com.arrivelog.domain.model.DayStatus
import com.arrivelog.domain.model.DetectionTuning
import com.arrivelog.domain.model.SignalSource
import com.arrivelog.domain.model.WorkDay
import com.arrivelog.domain.repository.SettingsRepository
import com.arrivelog.domain.repository.WorkDayRepository
import com.arrivelog.domain.stats.StatsPeriod
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Week / month switching and navigation over a fixed clock (Wed 28 Oct 2026, Dubai). */
@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModelTest {

    private val dubai = ZoneId.of("Asia/Dubai")
    private val monday = LocalDate.of(2026, 10, 26)
    private val wednesday = monday.plusDays(2)
    private fun at(date: LocalDate, h: Int, m: Int): Instant = ZonedDateTime.of(date, LocalTime.of(h, m), dubai).toInstant()

    private val days = FakeWorkDays()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = StatsViewModel(FakeSettings(), days, DetectionTuning(), Clock.fixed(at(wednesday, 12, 0), dubai))

    @Test
    fun `month view rates this month's arrivals and cannot go into the future`() = runTest {
        days.rows[monday] = WorkDay(monday, arrivalAt = at(monday, 7, 55), arrivalSource = SignalSource.GEOFENCE, exitAt = at(monday, 17, 0), zoneId = dubai)
        days.rows[monday.plusDays(1)] = WorkDay(monday.plusDays(1), arrivalAt = at(monday.plusDays(1), 8, 12), arrivalSource = SignalSource.WIFI, zoneId = dubai)
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()

        val s = vm.state.value
        assertThat(s.period).isEqualTo(StatsPeriod.MONTH)
        assertThat(s.from).isEqualTo(LocalDate.of(2026, 10, 1))
        assertThat(s.canGoForward).isFalse()
        assertThat(s.stats?.ratedArrivals).isEqualTo(2)
        assertThat(s.stats?.lateDays).isEqualTo(1)
        assertThat(s.stats?.onTimePercent).isEqualTo(50)
        assertThat(s.stats?.daysWithHours).isEqualTo(1)
        assertThat(s.stats?.points).hasSize(2)
    }

    @Test
    fun `week view and going back then forward again`() = runTest {
        days.rows[monday] = WorkDay(monday, arrivalAt = at(monday, 7, 55), arrivalSource = SignalSource.GEOFENCE, zoneId = dubai)
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }
        vm.setPeriod(StatsPeriod.WEEK)
        runCurrent()
        assertThat(vm.state.value.from).isEqualTo(monday)
        assertThat(vm.state.value.to).isEqualTo(monday.plusDays(6))

        vm.previous()
        runCurrent()
        assertThat(vm.state.value.from).isEqualTo(monday.minusWeeks(1))
        assertThat(vm.state.value.canGoForward).isTrue()
        assertThat(vm.state.value.stats?.ratedArrivals).isEqualTo(0)

        vm.next()
        runCurrent()
        assertThat(vm.state.value.from).isEqualTo(monday)
        assertThat(vm.state.value.canGoForward).isFalse()
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
        }

        override suspend fun save(day: WorkDay) {
            rows[day.date] = day
        }
    }
}
