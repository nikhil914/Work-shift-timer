package com.arrivelog.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arrivelog.background.DetectionActions
import com.arrivelog.domain.home.DaySummarizer
import com.arrivelog.domain.home.DaySummary
import com.arrivelog.domain.home.WeekGroup
import com.arrivelog.domain.model.ScheduleSettings
import com.arrivelog.domain.model.WorkDay
import com.arrivelog.domain.repository.SettingsRepository
import com.arrivelog.domain.repository.WorkDayRepository
import com.arrivelog.domain.usecase.DayEdit
import com.arrivelog.domain.usecase.EditResult
import com.arrivelog.domain.usecase.EditDayUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class HomeUiState(
    val loading: Boolean = true,
    val today: DaySummary? = null,
    val zone: ZoneId = ZoneId.systemDefault(),
    /** Newest week first. */
    val weeks: List<WeekGroup> = emptyList(),
    /** Past working days with neither an arrival nor a status ("never blank"). */
    val attentionCount: Int = 0,
    val canShowOlder: Boolean = false,
) {
    fun summaryFor(date: LocalDate): DaySummary? =
        if (today?.date == date) today else weeks.firstNotNullOfOrNull { w -> w.days.firstOrNull { it.date == date } }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val workDays: WorkDayRepository,
    private val summarizer: DaySummarizer,
    private val editDay: EditDayUseCase,
    private val actions: DetectionActions,
    private val clock: Clock,
) : ViewModel() {

    private val weeksShown = MutableStateFlow(INITIAL_WEEKS)

    /**
     * Drives the live "time at work" counter and the day rollover. It only runs while the screen
     * collects state (WhileSubscribed), so nothing ticks in the background (spec 5b, UI cost).
     */
    private val now: StateFlow<Instant> = flow {
        while (true) {
            emit(clock.instant())
            delay(TICK_MS)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), clock.instant())

    private val today = now.map { it.atZone(clock.zone).toLocalDate() }.distinctUntilChanged()

    private data class Range(val from: LocalDate, val to: LocalDate, val canShowOlder: Boolean)

    private val range = combine(today, workDays.observeEarliestDate(), weeksShown) { today, earliest, weeks ->
        // The log starts at the first day the app recorded anything, so a fresh install does not
        // open on weeks of "needs attention".
        val oldestShown = DaySummarizer.weekStart(today).minusWeeks(weeks - 1L)
        val first = earliest?.coerceAtMost(today) ?: today
        Range(maxOf(first, oldestShown), today, first.isBefore(oldestShown))
    }.distinctUntilChanged()

    private val rows = range.flatMapLatest { r -> workDays.observeRange(r.from, r.to).map { r to it } }

    val state: StateFlow<HomeUiState> = combine(settings.settings, rows, now) { app, (r, days), instant ->
        build(app.schedule, r, days, instant)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUiState())

    private fun build(schedule: ScheduleSettings, r: Range, days: List<WorkDay>, instant: Instant): HomeUiState {
        val weeks = summarizer.log(r.from, r.to, schedule, days, instant)
        val todaySummary = weeks.firstOrNull()?.days?.firstOrNull { it.date == r.to }
            ?: summarizer.summarize(r.to, schedule, days.firstOrNull { it.date == r.to }, instant)
        return HomeUiState(
            loading = false,
            today = todaySummary,
            zone = clock.zone,
            weeks = weeks,
            attentionCount = weeks.sumOf { w -> w.days.count { it.needsAttention && it.date != r.to } },
            canShowOlder = r.canShowOlder,
        )
    }

    /** For a day outside the visible range (e.g. an old notification). */
    suspend fun summaryOf(date: LocalDate): DaySummary =
        state.value.summaryFor(date) ?: summarizer.summarize(date, settings.current().schedule, workDays.get(date), clock.instant())

    fun logNow() {
        viewModelScope.launch { actions.logNow() }
    }

    fun showOlderWeeks() {
        weeksShown.value += MORE_WEEKS
    }

    fun save(date: LocalDate, edit: DayEdit, onResult: (EditResult) -> Unit) {
        viewModelScope.launch {
            val result = editDay(date, edit)
            if (result is EditResult.Saved) actions.afterUserEdit(date)
            onResult(result)
        }
    }

    private companion object {
        const val INITIAL_WEEKS = 8
        const val MORE_WEEKS = 8
        const val TICK_MS = 30_000L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
