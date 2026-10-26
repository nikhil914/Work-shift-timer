package com.arrivelog.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arrivelog.domain.engine.ArrivalDecisionEngine
import com.arrivelog.domain.engine.DecisionContext
import com.arrivelog.domain.engine.ListenerPlan
import com.arrivelog.domain.engine.ListenerPlanner
import com.arrivelog.domain.engine.MissedEntryDecision
import com.arrivelog.domain.model.AppSettings
import com.arrivelog.domain.model.DetectionMode
import com.arrivelog.domain.model.DetectionState
import com.arrivelog.domain.model.RawEvent
import com.arrivelog.domain.model.Signal
import com.arrivelog.domain.model.WifiNetwork
import com.arrivelog.domain.model.WorkDay
import com.arrivelog.domain.model.Workplace
import com.arrivelog.domain.repository.DetectionStateRepository
import com.arrivelog.domain.repository.EventLogRepository
import com.arrivelog.domain.repository.SettingsRepository
import com.arrivelog.domain.repository.WorkDayRepository
import com.arrivelog.domain.repository.WorkplaceRepository
import com.arrivelog.domain.schedule.DayTimeline
import com.arrivelog.domain.schedule.ResolvedDay
import com.arrivelog.domain.schedule.ScheduleResolver
import com.arrivelog.domain.usecase.CheckMissedEntryUseCase
import com.arrivelog.domain.usecase.ProcessSignalUseCase
import com.arrivelog.domain.usecase.SettleDayUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.inject.Inject

/** Phase 1 only: exercises engine + Room + DataStore on a real device. Replaced by Home in Phase 4. */
data class ShellUiState(
    val loading: Boolean = true,
    val date: LocalDate? = null,
    val zone: ZoneId? = null,
    val resolved: ResolvedDay? = null,
    val timeline: DayTimeline? = null,
    val day: WorkDay? = null,
    val state: DetectionState? = null,
    val plan: ListenerPlan? = null,
    val settings: AppSettings = AppSettings(),
    val workplaces: List<Workplace> = emptyList(),
    val events: List<RawEvent> = emptyList(),
)

enum class SimAction { WIFI_CONNECTED, WIFI_LOST, GEOFENCE_ENTER, GEOFENCE_DWELL, GEOFENCE_EXIT, MANUAL }

sealed interface ShellMessage {
    data class Result(val text: String) : ShellMessage
    data object BadTime : ShellMessage
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ShellViewModel @Inject constructor(
    private val engine: ArrivalDecisionEngine,
    private val planner: ListenerPlanner,
    private val settingsRepo: SettingsRepository,
    private val workplaceRepo: WorkplaceRepository,
    private val workDayRepo: WorkDayRepository,
    private val stateRepo: DetectionStateRepository,
    eventLog: EventLogRepository,
    private val processSignal: ProcessSignalUseCase,
    private val settleDay: SettleDayUseCase,
    private val checkMissedEntry: CheckMissedEntryUseCase,
) : ViewModel() {

    private val today = MutableStateFlow(engine.today())

    private val _signalTime = MutableStateFlow(LocalTime.now(engine.zone).format(TIME_FORMAT))
    val signalTime: StateFlow<String> = _signalTime.asStateFlow()

    private val _message = MutableStateFlow<ShellMessage?>(null)
    val message: StateFlow<ShellMessage?> = _message.asStateFlow()

    val state: StateFlow<ShellUiState> = today.flatMapLatest { date ->
        combine(
            settingsRepo.settings,
            workplaceRepo.observeAll(),
            workDayRepo.observe(date),
            stateRepo.observe(date),
            eventLog.observeRecent(EVENT_LIMIT),
        ) { settings, workplaces, day, detection, events ->
            val ctx = DecisionContext(
                date = date,
                settings = settings.detection,
                schedule = settings.schedule,
                workplaces = workplaces,
                day = day,
                state = detection ?: DetectionState(date),
            )
            ShellUiState(
                loading = false,
                date = date,
                zone = engine.zone,
                resolved = ScheduleResolver.resolve(date, settings.schedule),
                timeline = engine.timelineFor(date, settings.schedule),
                day = day,
                state = detection,
                plan = planner.plan(ctx),
                settings = settings,
                workplaces = workplaces,
                events = events,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ShellUiState())

    /** Called on resume: picks up a new day or a changed time zone, and commits a due pending exit. */
    fun onResume() {
        today.value = engine.today()
        viewModelScope.launch { settleDay(today.value) }
    }

    fun onSignalTimeChange(text: String) {
        _signalTime.value = text
    }

    fun simulate(action: SimAction, demoWorkplaceName: String) {
        val time = parseTime(_signalTime.value) ?: run {
            _message.value = ShellMessage.BadTime
            return
        }
        viewModelScope.launch {
            val workplace = ensureWorkplace(demoWorkplaceName)
            val at = ZonedDateTime.of(today.value, time, engine.zone).toInstant()
            val now = engine.now()
            val wifi = WifiNetwork("\"${workplace.ssids.firstOrNull() ?: DEMO_SSID}\"", DEMO_BSSID)
            val signal = when (action) {
                SimAction.WIFI_CONNECTED -> Signal.WifiConnected(wifi, at, now)
                SimAction.WIFI_LOST -> Signal.WifiLost(wifi, at, now)
                SimAction.GEOFENCE_ENTER -> Signal.GeofenceEnter(workplace.id, at, now, accuracyM = 20f)
                SimAction.GEOFENCE_DWELL -> Signal.GeofenceDwell(workplace.id, at, now, accuracyM = 20f)
                SimAction.GEOFENCE_EXIT -> Signal.GeofenceExit(workplace.id, at, now, accuracyM = 20f)
                SimAction.MANUAL -> Signal.Manual(at, now)
            }
            _message.value = ShellMessage.Result(processSignal(signal).decision.reasonText)
        }
    }

    fun settleNow() = viewModelScope.launch {
        _message.value = ShellMessage.Result(settleDay(today.value).reasonText)
    }

    fun runMissedEntryCheck() = viewModelScope.launch {
        val text = when (val d = checkMissedEntry(today.value)) {
            is MissedEntryDecision.NotYet -> "NOT_YET"
            is MissedEntryDecision.NotNeeded -> d.reason.name
            is MissedEntryDecision.Notify -> "NOTIFY" + (d.suggestedArrival?.let { "@" + it.atZone(engine.zone).format(TIME_FORMAT) } ?: "")
        }
        _message.value = ShellMessage.Result(text)
    }

    fun resetToday() = viewModelScope.launch {
        workDayRepo.delete(today.value)
        stateRepo.delete(today.value)
        _message.value = null
    }

    fun setMode(mode: DetectionMode) = viewModelScope.launch {
        settingsRepo.update { it.copy(detection = it.detection.copy(mode = mode)) }
    }

    fun setExitTracking(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.update { it.copy(detection = it.detection.copy(exitTrackingEnabled = enabled)) }
    }

    private suspend fun ensureWorkplace(demoName: String): Workplace {
        workplaceRepo.getAll().firstOrNull()?.let { return it }
        // Placeholder until the Phase 2 wizard: central Abu Dhabi, nothing real is monitored.
        val demo = Workplace(name = demoName, latitude = 24.4539, longitude = 54.3773, ssids = setOf(DEMO_SSID))
        val id = workplaceRepo.upsert(demo)
        return demo.copy(id = id)
    }

    private fun parseTime(text: String): LocalTime? = try {
        LocalTime.parse(text.trim(), INPUT_FORMAT)
    } catch (_: DateTimeParseException) {
        null
    }

    companion object {
        const val DEMO_SSID = "ArriveLog-Demo"
        private const val DEMO_BSSID = "02:ab:cd:ef:00:01"
        private const val EVENT_LIMIT = 30
        private const val STOP_TIMEOUT_MS = 5_000L
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        private val INPUT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")
    }
}
