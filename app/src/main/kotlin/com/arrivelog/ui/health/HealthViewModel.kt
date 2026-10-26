package com.arrivelog.ui.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arrivelog.background.ArmingStateStore
import com.arrivelog.background.DetectionCoordinator
import com.arrivelog.domain.engine.ListenerPlan
import com.arrivelog.domain.health.HealthCheck
import com.arrivelog.domain.health.HealthEvaluator
import com.arrivelog.domain.model.DetectionMode
import com.arrivelog.domain.model.RawEvent
import com.arrivelog.domain.model.RawEventType
import com.arrivelog.domain.repository.EventLogRepository
import com.arrivelog.domain.repository.SettingsRepository
import com.arrivelog.domain.setup.DeviceState
import com.arrivelog.platform.DeviceStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

data class HealthUiState(
    val loading: Boolean = true,
    val checks: List<HealthCheck> = emptyList(),
    val allGood: Boolean = false,
    val expected: ListenerPlan? = null,
    val lastRegisteredAt: Instant? = null,
    val lastError: String? = null,
    val lastErrorAt: Instant? = null,
    /** Last geofence / Wi-Fi / manual event, i.e. the last time the phone actually told us something. */
    val lastEvent: RawEvent? = null,
    val zone: ZoneId = ZoneId.systemDefault(),
)

private data class Snapshot(val device: DeviceState, val mode: DetectionMode, val expected: ListenerPlan)

/** Spec section 4 Health screen. Permissions are re-read on every resume (the user may fix them in Settings). */
@HiltViewModel
class HealthViewModel @Inject constructor(
    private val deviceStatus: DeviceStatus,
    private val settings: SettingsRepository,
    private val coordinator: DetectionCoordinator,
    arming: ArmingStateStore,
    eventLog: EventLogRepository,
    private val clock: Clock,
) : ViewModel() {

    private val snapshot = MutableStateFlow<Snapshot?>(null)

    val state: StateFlow<HealthUiState> = combine(
        snapshot.filterNotNull(),
        arming.state,
        eventLog.observeRecent(RECENT),
    ) { snap, arm, events ->
        val checks = HealthEvaluator.evaluate(snap.device, snap.mode, snap.expected, arm.geofenceArming, arm.wifiArmed)
        HealthUiState(
            loading = false,
            checks = checks,
            allGood = HealthEvaluator.allGood(checks),
            expected = snap.expected,
            lastRegisteredAt = arm.lastRegisteredAt,
            lastError = arm.lastError,
            lastErrorAt = arm.lastErrorAt,
            lastEvent = events.firstOrNull { it.type != RawEventType.SYSTEM },
            zone = clock.zone,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HealthUiState())

    fun refresh() = viewModelScope.launch {
        snapshot.value = Snapshot(deviceStatus.snapshot(), settings.current().detection.mode, coordinator.expectedPlan())
    }

    /** "Re-arm": register again even if nothing looks changed (the system may have dropped them). */
    fun rearm() = viewModelScope.launch {
        coordinator.sync("HEALTH", force = true)
        refresh()
    }

    private companion object {
        const val RECENT = 100
    }
}
