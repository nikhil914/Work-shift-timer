package com.arrivelog.ui.setup.wifi

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.arrivelog.R
import com.arrivelog.domain.model.AppSettings
import com.arrivelog.domain.model.WifiNetwork
import com.arrivelog.domain.model.Workplace
import com.arrivelog.domain.repository.SettingsRepository
import com.arrivelog.domain.repository.WorkplaceRepository
import com.arrivelog.domain.wifi.WifiMatcher
import com.arrivelog.platform.DeviceStatus
import com.arrivelog.platform.ScannedNetwork
import com.arrivelog.platform.SettingsIntents
import com.arrivelog.platform.WifiSource
import com.arrivelog.ui.common.CardTone
import com.arrivelog.ui.common.SectionCard
import com.arrivelog.ui.common.StepScaffold
import com.arrivelog.ui.common.rememberFineLocationRequest
import com.arrivelog.ui.nav.SetupStep
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One row in the picker: an SSID with every access point (BSSID) seen for it. */
data class SsidCandidate(val ssid: String, val bssids: Set<String>, val connected: Boolean, val levelDbm: Int?)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WifiSetupViewModel @Inject constructor(
    private val workplaces: WorkplaceRepository,
    private val settingsRepo: SettingsRepository,
    private val wifi: WifiSource,
    val deviceStatus: DeviceStatus,
) : ViewModel() {

    private val _hasPermission = MutableStateFlow(deviceStatus.granted(Manifest.permission.ACCESS_FINE_LOCATION))
    val hasPermission: StateFlow<Boolean> = _hasPermission.asStateFlow()

    val workplaceList: StateFlow<List<Workplace>> =
        workplaces.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<AppSettings> =
        settingsRepo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    /** Collected only while the screen is visible and permission is granted. */
    val connected: StateFlow<WifiNetwork?> = _hasPermission
        .flatMapLatest { granted -> if (granted) wifi.connected() else flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _scan = MutableStateFlow<List<ScannedNetwork>>(emptyList())
    val scan: StateFlow<List<ScannedNetwork>> = _scan.asStateFlow()

    private val _selectedId = MutableStateFlow<Long?>(null)
    val selectedId: StateFlow<Long?> = _selectedId.asStateFlow()

    fun onResume() {
        _hasPermission.value = deviceStatus.granted(Manifest.permission.ACCESS_FINE_LOCATION)
        _scan.value = wifi.cachedScanResults()
    }

    fun select(id: Long) {
        _selectedId.value = id
    }

    /** User tap only. Results arrive a few seconds later in the cached list. */
    fun rescan() = viewModelScope.launch {
        wifi.requestScan()
        repeat(3) {
            delay(2_000)
            _scan.value = wifi.cachedScanResults()
        }
    }

    fun candidates(connected: WifiNetwork?, selected: Workplace?): List<SsidCandidate> {
        val connectedSsid = WifiMatcher.normalizeSsid(connected?.ssid)
        val connectedBssid = WifiMatcher.normalizeBssid(connected?.bssid)
        val byScan = _scan.value.groupBy { it.ssid }
        val names = buildList {
            connectedSsid?.let(::add)
            addAll(byScan.keys)
            selected?.ssids?.let(::addAll)
        }.distinct()
        return names.map { ssid ->
            val aps = byScan[ssid].orEmpty()
            SsidCandidate(
                ssid = ssid,
                bssids = aps.mapNotNull { it.bssid }.toSet() + listOfNotNull(connectedBssid.takeIf { ssid == connectedSsid }),
                connected = ssid == connectedSsid,
                levelDbm = aps.maxOfOrNull { it.levelDbm },
            )
        }
    }

    fun toggle(workplace: Workplace, candidate: SsidCandidate) = viewModelScope.launch {
        val updated = if (candidate.ssid in workplace.ssids) {
            workplace.copy(ssids = workplace.ssids - candidate.ssid, bssids = workplace.bssids - candidate.bssids)
        } else {
            // BSSIDs are captured now so "also match BSSID" works later without another visit.
            workplace.copy(ssids = workplace.ssids + candidate.ssid, bssids = workplace.bssids + candidate.bssids)
        }
        workplaces.upsert(updated)
    }

    fun addManual(workplace: Workplace, raw: String) = viewModelScope.launch {
        val ssid = WifiMatcher.normalizeSsid(raw) ?: return@launch
        workplaces.upsert(workplace.copy(ssids = workplace.ssids + ssid))
    }

    fun setMatchBssid(on: Boolean) = viewModelScope.launch {
        settingsRepo.update { it.copy(detection = it.detection.copy(matchBssid = on)) }
    }

    fun setInRange(on: Boolean) = viewModelScope.launch {
        settingsRepo.update { it.copy(detection = it.detection.copy(wifiInRangeCountsAsPresent = on)) }
    }

    fun permissionResult(granted: Boolean) {
        _hasPermission.value = granted
        if (granted) _scan.value = wifi.cachedScanResults()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WifiSetupScreen(
    wizard: Boolean,
    onNext: () -> Unit,
    viewModel: WifiSetupViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val hasPermission by viewModel.hasPermission.collectAsStateWithLifecycle()
    val workplaces by viewModel.workplaceList.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val scan by viewModel.scan.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedId.collectAsStateWithLifecycle()
    val selected = workplaces.firstOrNull { it.id == selectedId } ?: workplaces.firstOrNull()
    val requestLocation = rememberFineLocationRequest(viewModel::permissionResult)

    LifecycleResumeEffect(Unit) {
        viewModel.onResume()
        onPauseOrDispose { }
    }

    StepScaffold(
        title = stringResource(R.string.wifi_title),
        step = SetupStep.WIFI,
        wizard = wizard,
        primaryEnabled = true,
        onPrimary = onNext,
        primaryLabel = if (wizard && workplaces.none { it.ssids.isNotEmpty() }) stringResource(R.string.action_skip) else null,
    ) {
        item { Text(stringResource(R.string.wifi_intro), style = MaterialTheme.typography.bodyMedium) }

        if (!hasPermission) {
            item {
                SectionCard(tone = CardTone.Warning) {
                    Text(stringResource(R.string.wifi_permission_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.wifi_permission_body), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = requestLocation) { Text(stringResource(R.string.action_allow)) }
                        TextButton(onClick = { SettingsIntents.launch(context, SettingsIntents.appDetails(context)) }) {
                            Text(stringResource(R.string.action_open_settings))
                        }
                    }
                }
            }
        }

        if (workplaces.size > 1) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    workplaces.forEach { w ->
                        FilterChip(selected = w.id == selected?.id, onClick = { viewModel.select(w.id) }, label = { Text(w.name) })
                    }
                }
            }
        }

        if (selected == null) {
            item { Text(stringResource(R.string.wifi_no_workplace), style = MaterialTheme.typography.bodyMedium) }
        } else {
            val candidates = viewModel.candidates(connected, selected)
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.wifi_for_workplace, selected.name),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::rescan, enabled = hasPermission) { Text(stringResource(R.string.wifi_rescan)) }
                }
            }
            if (candidates.isEmpty()) {
                item { Text(stringResource(R.string.wifi_none_visible), style = MaterialTheme.typography.bodySmall) }
            }
            items(candidates, key = { it.ssid }) { c ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(checked = c.ssid in selected.ssids, onCheckedChange = { viewModel.toggle(selected, c) })
                    Column(Modifier.weight(1f)) {
                        Text(c.ssid, style = MaterialTheme.typography.bodyLarge)
                        val detail = buildList {
                            if (c.connected) add(stringResource(R.string.wifi_connected))
                            c.levelDbm?.let { add(stringResource(R.string.wifi_signal, it)) }
                            if (c.bssids.isNotEmpty()) add(pluralStringResource(R.plurals.wifi_access_points, c.bssids.size, c.bssids.size))
                        }.joinToString(" · ")
                        if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { ManualSsid(onAdd = { viewModel.addManual(selected, it) }) }
        }

        item {
            SectionCard {
                ToggleRow(
                    title = stringResource(R.string.wifi_match_bssid),
                    body = stringResource(R.string.wifi_match_bssid_body),
                    checked = settings.detection.matchBssid,
                    onChange = viewModel::setMatchBssid,
                )
                ToggleRow(
                    title = stringResource(R.string.wifi_in_range),
                    body = stringResource(R.string.wifi_in_range_body),
                    checked = settings.detection.wifiInRangeCountsAsPresent,
                    onChange = viewModel::setInRange,
                )
            }
        }
        // Note shown so nobody wonders why the list is short: scans are throttled by Android.
        if (hasPermission && scan.isEmpty()) {
            item { Text(stringResource(R.string.wifi_scan_note), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun ManualSsid(onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.wifi_manual)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = { onAdd(text); text = "" }, enabled = text.isNotBlank()) { Text(stringResource(R.string.action_add)) }
    }
}

@Composable
fun ToggleRow(title: String, body: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (body != null) Text(body, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
