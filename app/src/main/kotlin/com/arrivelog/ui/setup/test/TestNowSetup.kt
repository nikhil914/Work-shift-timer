package com.arrivelog.ui.setup.test

import android.Manifest
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.arrivelog.R
import com.arrivelog.domain.engine.ArrivalDecisionEngine
import com.arrivelog.domain.geo.GeoMath
import com.arrivelog.domain.model.AppSettings
import com.arrivelog.domain.model.WifiNetwork
import com.arrivelog.domain.model.Workplace
import com.arrivelog.domain.repository.SettingsRepository
import com.arrivelog.domain.repository.WorkplaceRepository
import com.arrivelog.domain.schedule.DayTimeline
import com.arrivelog.domain.setup.DeviceState
import com.arrivelog.domain.setup.PresenceCheck
import com.arrivelog.domain.setup.ReadinessIssue
import com.arrivelog.domain.setup.SetupReadiness
import com.arrivelog.domain.wifi.WifiMatch
import com.arrivelog.domain.wifi.WifiMatcher
import com.arrivelog.platform.DeviceStatus
import com.arrivelog.platform.Fix
import com.arrivelog.platform.LocationSource
import com.arrivelog.platform.WifiSource
import com.arrivelog.ui.common.CardTone
import com.arrivelog.ui.common.HHMM
import com.arrivelog.ui.common.SectionCard
import com.arrivelog.ui.common.StatusDot
import com.arrivelog.ui.common.StepScaffold
import com.arrivelog.ui.nav.SetupStep
import com.arrivelog.ui.setup.mode.modeTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

data class FenceCheck(val workplace: Workplace, val distanceM: Int, val inside: Boolean)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TestNowViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    workplaceRepo: WorkplaceRepository,
    private val deviceStatus: DeviceStatus,
    private val locationSource: LocationSource,
    wifi: WifiSource,
    private val engine: ArrivalDecisionEngine,
) : ViewModel() {
    val settings: StateFlow<AppSettings?> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val workplaces: StateFlow<List<Workplace>> = workplaceRepo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _device = MutableStateFlow(deviceStatus.snapshot())
    val device: StateFlow<DeviceState> = _device.asStateFlow()

    val connected: StateFlow<WifiNetwork?> = _device
        .flatMapLatest { if (it.fineLocation) wifi.connected() else flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _fix = MutableStateFlow<Fix?>(null)
    val fix: StateFlow<Fix?> = _fix.asStateFlow()

    private val _locating = MutableStateFlow(false)
    val locating: StateFlow<Boolean> = _locating.asStateFlow()

    private val _finished = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val finished = _finished.asSharedFlow()

    fun refresh() {
        _device.value = deviceStatus.snapshot()
    }

    /** One fix per tap; nothing continuous. */
    fun locate() = viewModelScope.launch {
        if (!deviceStatus.granted(Manifest.permission.ACCESS_FINE_LOCATION)) return@launch
        _locating.value = true
        _fix.value = locationSource.currentLocation()
        _locating.value = false
    }

    fun fences(fix: Fix?, workplaces: List<Workplace>): List<FenceCheck> = if (fix == null) {
        emptyList()
    } else {
        workplaces.map { w ->
            val d = GeoMath.distanceMeters(fix.latitude, fix.longitude, w.latitude, w.longitude)
            FenceCheck(w, d.roundToInt(), d <= w.radiusMeters)
        }
    }

    fun wifiMatch(network: WifiNetwork?, workplaces: List<Workplace>, settings: AppSettings): WifiMatch? =
        network?.let { WifiMatcher.match(it, workplaces, settings.detection.matchBssid) }

    fun today(settings: AppSettings): DayTimeline? = engine.timelineFor(engine.today(), settings.schedule)

    fun finish() = viewModelScope.launch {
        settingsRepo.update { it.copy(setupCompleted = true) }
        _finished.tryEmit(Unit)
    }
}

@Composable
fun TestNowScreen(wizard: Boolean, onFinished: () -> Unit, viewModel: TestNowViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val workplaces by viewModel.workplaces.collectAsStateWithLifecycle()
    val device by viewModel.device.collectAsStateWithLifecycle()
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val fix by viewModel.fix.collectAsStateWithLifecycle()
    val locating by viewModel.locating.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(device.fineLocation) { if (device.fineLocation) viewModel.locate() }
    LaunchedEffect(Unit) { viewModel.finished.collect { onFinished() } }

    val s = settings ?: return
    val issues = SetupReadiness.issues(s, workplaces, device)
    val fences = viewModel.fences(fix, workplaces)
    val match = viewModel.wifiMatch(connected, workplaces, s)
    val wifiIds = (match as? WifiMatch.Matched)?.workplaceIds.orEmpty()
    val insideIds = fences.filter { it.inside }.map { it.workplace.id }.toSet()
    val wouldDetect = PresenceCheck.wouldDetect(s.detection.mode, insideIds, wifiIds)

    StepScaffold(
        title = stringResource(R.string.test_title),
        step = SetupStep.TEST,
        wizard = wizard,
        primaryEnabled = true,
        onPrimary = { if (wizard) viewModel.finish() else onFinished() },
        primaryLabel = if (wizard) stringResource(R.string.test_finish) else null,
    ) {
        item {
            SectionCard(tone = if (issues.none { it.blocking }) CardTone.Good else CardTone.Warning) {
                Text(
                    stringResource(if (issues.none { it.blocking }) R.string.test_ready else R.string.test_not_ready),
                    style = MaterialTheme.typography.titleSmall,
                )
                issues.forEach { issue ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(false)
                        Text(stringResource(issueText(issue)), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item {
            SectionCard {
                Text(stringResource(R.string.test_location), style = MaterialTheme.typography.titleSmall)
                when {
                    !device.fineLocation -> Text(stringResource(R.string.test_location_no_permission), style = MaterialTheme.typography.bodySmall)
                    locating -> CircularProgressIndicator()
                    fix == null -> Text(stringResource(R.string.test_location_none), style = MaterialTheme.typography.bodySmall)
                    else -> {
                        fix?.accuracyM?.let { Text(stringResource(R.string.test_accuracy, it.roundToInt()), style = MaterialTheme.typography.bodySmall) }
                        fences.forEach { f ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusDot(f.inside)
                                Text(
                                    stringResource(
                                        if (f.inside) R.string.test_inside else R.string.test_outside,
                                        f.workplace.name,
                                        f.distanceM,
                                        f.workplace.radiusMeters,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                OutlinedButton(onClick = viewModel::locate, enabled = device.fineLocation && !locating) {
                    Text(stringResource(R.string.test_locate_again))
                }
            }
        }

        item {
            SectionCard {
                Text(stringResource(R.string.test_wifi), style = MaterialTheme.typography.titleSmall)
                val ssid = WifiMatcher.normalizeSsid(connected?.ssid)
                Text(
                    when {
                        connected == null -> stringResource(R.string.test_wifi_none)
                        ssid == null -> stringResource(R.string.test_wifi_hidden)
                        else -> stringResource(R.string.test_wifi_connected, ssid)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (match != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(match is WifiMatch.Matched)
                        Text(
                            stringResource(
                                when (match) {
                                    is WifiMatch.Matched -> R.string.test_wifi_match
                                    WifiMatch.NotOffice -> R.string.test_wifi_not_office
                                    WifiMatch.BssidMismatch -> R.string.test_wifi_bssid
                                    WifiMatch.UnknownSsid -> R.string.test_wifi_hidden
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        item {
            SectionCard(tone = if (wouldDetect) CardTone.Good else CardTone.Normal) {
                Text(stringResource(R.string.test_verdict_title, stringResource(modeTitle(s.detection.mode))), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(if (wouldDetect) R.string.test_verdict_yes else R.string.test_verdict_no),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val t = viewModel.today(s)
                Text(
                    if (t == null) {
                        stringResource(R.string.test_today_off)
                    } else {
                        stringResource(
                            R.string.test_today_window,
                            t.profile.name,
                            t.profile.entryWindowStart.format(HHMM),
                            t.profile.entryWindowEnd.format(HHMM),
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(stringResource(R.string.test_phase_note), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (wizard) {
            item { Button(onClick = viewModel::refresh) { Text(stringResource(R.string.test_refresh)) } }
        }
    }
}

fun issueText(i: ReadinessIssue): Int = when (i) {
    ReadinessIssue.NO_WORKPLACE -> R.string.issue_no_workplace
    ReadinessIssue.NO_OFFICE_WIFI -> R.string.issue_no_wifi
    ReadinessIssue.NO_WORKING_DAYS -> R.string.issue_no_days
    ReadinessIssue.FINE_LOCATION -> R.string.issue_fine
    ReadinessIssue.BACKGROUND_LOCATION -> R.string.issue_background
    ReadinessIssue.LOCATION_OFF -> R.string.issue_location_off
    ReadinessIssue.NOTIFICATIONS -> R.string.issue_notifications
    ReadinessIssue.BATTERY_RESTRICTED -> R.string.issue_battery
    ReadinessIssue.NEARBY_WIFI -> R.string.issue_nearby
    ReadinessIssue.WIFI_OFF -> R.string.issue_wifi_off
}
