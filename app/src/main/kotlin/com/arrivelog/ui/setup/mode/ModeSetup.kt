package com.arrivelog.ui.setup.mode

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.arrivelog.R
import com.arrivelog.domain.model.AppSettings
import com.arrivelog.domain.model.DetectionMode
import com.arrivelog.domain.model.DetectionSettings
import com.arrivelog.domain.repository.SettingsRepository
import com.arrivelog.ui.common.SectionCard
import com.arrivelog.ui.common.StepScaffold
import com.arrivelog.ui.nav.SetupStep
import com.arrivelog.ui.setup.wifi.ToggleRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ModeViewModel @Inject constructor(private val repo: SettingsRepository) : ViewModel() {
    val settings: StateFlow<AppSettings> = repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private fun edit(f: (DetectionSettings) -> DetectionSettings) = viewModelScope.launch {
        repo.update { it.copy(detection = f(it.detection)) }
    }

    fun setMode(m: DetectionMode) = edit { it.copy(mode = m) }
    fun setExitTracking(on: Boolean) = edit { it.copy(exitTrackingEnabled = on) }
    fun setExitReminder(on: Boolean) = edit { it.copy(exitReminderEnabled = on) }
    fun setFaster(on: Boolean) = edit { it.copy(fasterDetection = on) }
}

@Composable
fun ModeScreen(wizard: Boolean, onNext: () -> Unit, viewModel: ModeViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val d = settings.detection
    StepScaffold(
        title = stringResource(R.string.mode_title),
        step = SetupStep.MODE,
        wizard = wizard,
        primaryEnabled = true,
        onPrimary = onNext,
    ) {
        item {
            SectionCard {
                Column(Modifier.selectableGroup()) {
                    DetectionMode.entries.forEach { m ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = d.mode == m, onClick = { viewModel.setMode(m) }, role = Role.RadioButton),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = d.mode == m, onClick = null)
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(modeTitle(m)), style = MaterialTheme.typography.bodyLarge)
                                Text(stringResource(modeBody(m)), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        item {
            SectionCard {
                ToggleRow(stringResource(R.string.mode_exit), stringResource(R.string.mode_exit_body), d.exitTrackingEnabled, viewModel::setExitTracking)
                ToggleRow(
                    stringResource(R.string.mode_exit_reminder),
                    stringResource(R.string.mode_exit_reminder_body),
                    d.exitReminderEnabled,
                    viewModel::setExitReminder,
                    enabled = d.exitTrackingEnabled,
                )
                ToggleRow(stringResource(R.string.mode_faster), stringResource(R.string.mode_faster_body), d.fasterDetection, viewModel::setFaster)
            }
        }
    }
}

fun modeTitle(m: DetectionMode): Int = when (m) {
    DetectionMode.GEOFENCE_OR_WIFI -> R.string.mode_or
    DetectionMode.GEOFENCE_AND_WIFI -> R.string.mode_and
    DetectionMode.WIFI_ONLY -> R.string.mode_wifi
    DetectionMode.GEOFENCE_ONLY -> R.string.mode_geofence
}

private fun modeBody(m: DetectionMode): Int = when (m) {
    DetectionMode.GEOFENCE_OR_WIFI -> R.string.mode_or_body
    DetectionMode.GEOFENCE_AND_WIFI -> R.string.mode_and_body
    DetectionMode.WIFI_ONLY -> R.string.mode_wifi_body
    DetectionMode.GEOFENCE_ONLY -> R.string.mode_geofence_body
}
