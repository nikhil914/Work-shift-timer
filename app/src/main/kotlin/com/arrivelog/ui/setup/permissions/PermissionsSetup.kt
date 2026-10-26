package com.arrivelog.ui.setup.permissions

import android.Manifest
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arrivelog.R
import com.arrivelog.domain.setup.DeviceState
import com.arrivelog.platform.DeviceStatus
import com.arrivelog.platform.SettingsIntents
import com.arrivelog.ui.common.CardTone
import com.arrivelog.ui.common.SectionCard
import com.arrivelog.ui.common.StatusDot
import com.arrivelog.ui.common.StepScaffold
import com.arrivelog.ui.common.rememberFineLocationRequest
import com.arrivelog.ui.common.rememberPermissionRequest
import com.arrivelog.ui.nav.SetupStep
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class PermissionsViewModel @Inject constructor(val deviceStatus: DeviceStatus) : ViewModel() {
    private val _state = MutableStateFlow(deviceStatus.snapshot())
    val state: StateFlow<DeviceState> = _state.asStateFlow()

    fun refresh() {
        _state.value = deviceStatus.snapshot()
    }
}

/**
 * Section 5: one card per permission, in order, each with its explanation shown *before* the
 * system prompt (this is also the "prominent disclosure" Google Play requires for background
 * location). Every denial has a way back: "Open settings" appears once a request was refused.
 */
@Composable
fun PermissionsScreen(wizard: Boolean, onNext: () -> Unit, viewModel: PermissionsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val s by viewModel.state.collectAsStateWithLifecycle()
    var refused by rememberSaveable { mutableStateOf(setOf<String>()) }

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    fun onResult(key: String, granted: Boolean) {
        viewModel.refresh()
        refused = if (granted) refused - key else refused + key
    }

    val fine = rememberFineLocationRequest { onResult("fine", it) }
    val background = rememberPermissionRequest(Manifest.permission.ACCESS_BACKGROUND_LOCATION) { onResult("background", it) }
    // Launchers must be created unconditionally; below API 33 these two are never launched
    // (a harmless install-time permission stands in for the missing runtime one).
    val notifications = rememberPermissionRequest(
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else Manifest.permission.ACCESS_NETWORK_STATE,
    ) { onResult("notifications", it) }
    val nearby = rememberPermissionRequest(
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_NETWORK_STATE,
    ) { onResult("nearby", it) }
    val openAppSettings = { SettingsIntents.launch(context, SettingsIntents.appDetails(context)) }

    val requiredMissing = !s.fineLocation || !s.backgroundLocation || !s.locationEnabled

    StepScaffold(
        title = stringResource(R.string.perm_title),
        step = SetupStep.PERMISSIONS,
        wizard = wizard,
        primaryEnabled = true,
        onPrimary = onNext,
        primaryLabel = if (wizard && requiredMissing) stringResource(R.string.perm_continue_anyway) else null,
    ) {
        item { Text(stringResource(R.string.perm_intro), style = MaterialTheme.typography.bodyMedium) }

        item {
            PermissionCard(
                ok = s.fineLocation,
                title = stringResource(R.string.perm_fine_title),
                body = stringResource(R.string.perm_fine_body),
                refused = "fine" in refused,
                onAllow = fine,
                onSettings = openAppSettings,
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            item {
                PermissionCard(
                    ok = s.backgroundLocation,
                    title = stringResource(R.string.perm_bg_title),
                    body = stringResource(R.string.perm_bg_body),
                    extra = stringResource(R.string.perm_bg_how),
                    enabled = s.fineLocation,
                    refused = "background" in refused,
                    allowLabel = stringResource(R.string.perm_bg_allow),
                    onAllow = background,
                    onSettings = openAppSettings,
                )
            }
        }
        item {
            PermissionCard(
                ok = s.locationEnabled,
                title = stringResource(R.string.perm_location_on_title),
                body = stringResource(R.string.perm_location_on_body),
                allowLabel = stringResource(R.string.action_open_settings),
                onAllow = { SettingsIntents.launch(context, SettingsIntents.location()) },
            )
        }
        item {
            PermissionCard(
                ok = s.notifications,
                title = stringResource(R.string.perm_notif_title),
                body = stringResource(R.string.perm_notif_body),
                refused = "notifications" in refused,
                onAllow = if (Build.VERSION.SDK_INT >= 33) notifications else { { SettingsIntents.launch(context, SettingsIntents.notifications(context)) } },
                onSettings = { SettingsIntents.launch(context, SettingsIntents.notifications(context)) },
            )
        }
        if (Build.VERSION.SDK_INT >= 33) {
            item {
                PermissionCard(
                    ok = s.nearbyWifi,
                    title = stringResource(R.string.perm_nearby_title),
                    body = stringResource(R.string.perm_nearby_body),
                    refused = "nearby" in refused,
                    onAllow = nearby,
                    onSettings = openAppSettings,
                )
            }
        }
        item {
            PermissionCard(
                ok = s.batteryUnrestricted,
                title = stringResource(R.string.perm_battery_title),
                body = stringResource(R.string.perm_battery_body),
                extra = if (viewModel.deviceStatus.isSamsung) stringResource(R.string.perm_battery_samsung) else stringResource(R.string.perm_battery_generic),
                allowLabel = stringResource(R.string.perm_battery_open_list),
                onAllow = { SettingsIntents.launch(context, SettingsIntents.batteryOptimizationList()) },
                onSettings = openAppSettings,
                alwaysShowSettings = true,
                settingsLabel = stringResource(R.string.perm_battery_open_app),
            )
        }
        if (requiredMissing) {
            item {
                SectionCard(tone = CardTone.Warning) {
                    Text(stringResource(R.string.perm_required_missing), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PermissionCard(
    ok: Boolean,
    title: String,
    body: String,
    onAllow: () -> Unit,
    extra: String? = null,
    enabled: Boolean = true,
    refused: Boolean = false,
    allowLabel: String? = null,
    onSettings: (() -> Unit)? = null,
    alwaysShowSettings: Boolean = false,
    settingsLabel: String? = null,
) {
    SectionCard(tone = if (ok) CardTone.Good else CardTone.Normal) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(ok)
            Text(title, style = MaterialTheme.typography.titleSmall)
        }
        if (!ok) {
            Text(body, style = MaterialTheme.typography.bodySmall)
            if (extra != null) Text(extra, style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAllow, enabled = enabled) { Text(allowLabel ?: stringResource(R.string.action_allow)) }
                if (onSettings != null && (refused || alwaysShowSettings)) {
                    OutlinedButton(onClick = onSettings) { Text(settingsLabel ?: stringResource(R.string.action_open_settings)) }
                }
            }
            if (refused) Text(stringResource(R.string.perm_refused_hint), style = MaterialTheme.typography.bodySmall)
            if (!enabled) Text(stringResource(R.string.perm_needs_previous), style = MaterialTheme.typography.bodySmall)
        } else {
            Text(stringResource(R.string.perm_granted), style = MaterialTheme.typography.bodySmall)
        }
    }
}
