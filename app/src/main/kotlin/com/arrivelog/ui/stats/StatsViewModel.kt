package com.arrivelog.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arrivelog.domain.model.DetectionTuning
import com.arrivelog.domain.repository.SettingsRepository
import com.arrivelog.domain.repository.WorkDayRepository
import com.arrivelog.domain.stats.PeriodStats
import com.arrivelog.domain.stats.StatsCalculator
import com.arrivelog.domain.stats.StatsPeriod
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class StatsUiState(
    val loading: Boolean = true,
    val period: StatsPeriod = StatsPeriod.MONTH,
    /** First and last day of the selected week / month (the full period, not cut at today). */
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val stats: PeriodStats? = null,
    /** False on the current period: there is nothing to show in the future. */
    val canGoForward: Boolean = false,
    val zone: ZoneId = ZoneId.systemDefault(),
)

private data class Selection(val period: StatsPeriod, val anchor: LocalDate)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val workDays: WorkDayRepository,
    private val tuning: DetectionTuning,
    private val clock: Clock,
) : ViewModel() {

    private val selection = MutableStateFlow(Selection(StatsPeriod.MONTH, LocalDate.now(clock)))

    val state: StateFlow<StatsUiState> = selection.flatMapLatest { sel ->
        val range = sel.period.range(sel.anchor)
        combine(settings.settings, workDays.observeRange(range.start, range.endInclusive)) { app, records ->
            val now = clock.instant()
            val today = now.atZone(clock.zone).toLocalDate()
            StatsUiState(
                loading = false,
                period = sel.period,
                from = range.start,
                to = range.endInclusive,
                stats = StatsCalculator.compute(range, records, app.schedule, clock.zone, now, tuning),
                canGoForward = range.endInclusive.isBefore(today),
                zone = clock.zone,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    fun setPeriod(period: StatsPeriod) = selection.update { it.copy(period = period) }

    fun previous() = selection.update { it.copy(anchor = it.period.shift(it.anchor, -1)) }

    fun next() = selection.update { sel ->
        val shifted = sel.period.shift(sel.anchor, 1)
        if (shifted.isAfter(LocalDate.now(clock))) sel.copy(anchor = LocalDate.now(clock)) else sel.copy(anchor = shifted)
    }
}
