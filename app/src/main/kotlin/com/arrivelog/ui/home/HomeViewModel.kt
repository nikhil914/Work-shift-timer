package com.arrivelog.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arrivelog.background.ReplanTrigger
import com.arrivelog.domain.day.DayEdit
import com.arrivelog.domain.day.DayLogBuilder
import com.arrivelog.domain.day.DaySummary
import com.arrivelog.domain.day.DaySummaryCalculator
import com.arrivelog.domain.day.WeekGroup
import com.arrivelog.domain.model.AppSettings
import com.arrivelog.domain.model.WorkDay
import com.arrivelog.domain.repository.DetectionStateRepository
import com.arrivelog.domain.repository.SettingsRepository
import com.arrivelog.domain.repository.WorkDayRepository
import com.arrivelog.domain.usecase.EditDayUseCase
import com.arrivelog.domain.usecase.EditResult
import com.arrivelog.notify.EditFocus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val pendingExitAt: Instant? = null,
    val weeks: List<WeekGroup> = emptyList(),
    val needsAttention: List<DaySummary> = emptyList(),
    val zone: ZoneId = ZoneId.systemDefault(),
)

data class EditTarget(val date: LocalDate, val record: WorkDay, val focus: EditFocus)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val workDays: WorkDayRepository,
    private val states: DetectionStateRepository,
    private val editDay: EditDayUseCase,
    private val replan: ReplanTrigger,
    private val clock: Clock,
) : ViewModel() {

    /**
     * Ticks once a minute, only while the screen collects it (WhileSubscribed(0) +
     * collectAsStateWithLifecycle): the live "time at work" counter costs nothing in the background.
     */
    private val ticker: Flow<Instant> = flow {
        while (true) {
            val now = clock.instant()
            emit(now)
            delay(MINUTE_MS - now.toEpochMilli() % MINUTE_MS)
        }
    }

    private val today: Flow<LocalDate> = ticker.map { it.atZone(clock.zone).toLocalDate() }.distinctUntilChanged()

    val state: StateFlow<HomeUiState> = today.flatMapLatest { date ->
        combine(
            settings.settings,
            workDays.observeRange(date.minusDays(HISTORY_DAYS), date),
            states.observe(date),
            ticker,
        ) { app: AppSettings, records, detection, now ->
            val zone = clock.zone
            val weeks = DayLogBuilder.build(date.minusDays(HISTORY_DAYS), date, records, app.schedule, zone, now)
            HomeUiState(
                loading = false,
                today = DaySummaryCalculator.of(date, app.schedule, records.firstOrNull { it.date == date }, zone, now),
                pendingExitAt = detection?.pendingExit?.at,
                weeks = weeks,
                needsAttention = DayLogBuilder.needingAttention(weeks).filter { it.date != date },
                zone = zone,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), HomeUiState())

    private val _editing = MutableStateFlow<EditTarget?>(null)
    val editing: StateFlow<EditTarget?> = _editing.asStateFlow()

    private val _editError = MutableStateFlow<EditResult.Invalid?>(null)
    val editError: StateFlow<EditResult.Invalid?> = _editError.asStateFlow()

    fun openEditor(date: LocalDate, focus: EditFocus = EditFocus.TIME) = viewModelScope.launch {
        _editError.value = null
        _editing.value = EditTarget(date, workDays.get(date) ?: WorkDay(date), focus)
    }

    fun closeEditor() {
        _editing.value = null
        _editError.value = null
    }

    fun save(date: LocalDate, edit: DayEdit) = viewModelScope.launch {
        when (val r = editDay(date, edit)) {
            EditResult.Saved -> {
                closeEditor()
                replan.afterEdit(date)
            }
            is EditResult.Invalid -> _editError.value = r
        }
    }

    val zone: ZoneId get() = clock.zone

    private companion object {
        const val MINUTE_MS = 60_000L
        const val HISTORY_DAYS = 83L // 12 weeks
    }
}
