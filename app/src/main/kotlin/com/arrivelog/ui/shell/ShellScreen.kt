package com.arrivelog.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arrivelog.R
import com.arrivelog.domain.model.DayStatus
import com.arrivelog.domain.model.DetectionMode
import com.arrivelog.domain.model.RawEvent
import com.arrivelog.domain.model.SignalSource
import com.arrivelog.domain.schedule.ResolvedDay
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShellScreen(onSettings: () -> Unit, viewModel: ShellViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val signalTime by viewModel.signalTime.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.onResume()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.shell_title)) },
                actions = { TextButton(onClick = onSettings) { Text(stringResource(R.string.settings_title)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { TodayCard(state) }
            item {
                SettingsCard(
                    mode = state.settings.detection.mode,
                    exitTracking = state.settings.detection.exitTrackingEnabled,
                    onMode = viewModel::setMode,
                    onExitTracking = viewModel::setExitTracking,
                )
            }
            item {
                SimulateCard(
                    signalTime = signalTime,
                    message = message,
                    workplaceCount = state.workplaces.size,
                    ssid = state.workplaces.firstOrNull()?.ssids?.firstOrNull() ?: ShellViewModel.DEMO_SSID,
                    onTime = viewModel::onSignalTimeChange,
                    onSimulate = viewModel::simulate,
                    onSettle = { viewModel.settleNow() },
                    onMissed = { viewModel.runMissedEntryCheck() },
                    onReset = { viewModel.resetToday() },
                )
            }
            item {
                Text(stringResource(R.string.shell_events_title), style = MaterialTheme.typography.titleMedium)
            }
            if (state.events.isEmpty()) {
                item { Text(stringResource(R.string.shell_events_empty)) }
            }
            items(state.events, key = { it.id }) { event -> EventRow(event, state.zone ?: ZoneId.systemDefault()) }
        }
    }
}

@Composable
private fun TodayCard(state: ShellUiState) {
    val zone = state.day?.zoneId ?: state.zone ?: ZoneId.systemDefault()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.shell_today_title, state.date?.format(DATE_FORMAT).orEmpty()),
                style = MaterialTheme.typography.titleMedium,
            )
            when (val resolved = state.resolved) {
                is ResolvedDay.Working -> {
                    val p = resolved.profile
                    Text(
                        stringResource(
                            R.string.shell_day_working,
                            p.name,
                            p.entryWindowStart.format(TIME),
                            p.entryWindowEnd.format(TIME),
                            p.expectedStart.format(TIME),
                        ),
                    )
                }
                is ResolvedDay.Off -> Text(stringResource(R.string.shell_day_off, statusLabel(resolved.status)))
                null -> Unit
            }
            val day = state.day
            val arrival = day?.arrivalAt
            Text(
                text = if (arrival != null) {
                    stringResource(R.string.shell_arrival, arrival.fmt(zone), sourceLabel(day.arrivalSource))
                } else {
                    stringResource(R.string.shell_arrival_none)
                },
                style = MaterialTheme.typography.headlineSmall,
            )
            val exit = day?.exitAt
            Text(
                if (exit != null) {
                    stringResource(R.string.shell_exit, exit.fmt(zone), sourceLabel(day.exitSource))
                } else {
                    stringResource(R.string.shell_exit_none)
                },
            )
            state.state?.pendingExit?.let { Text(stringResource(R.string.shell_exit_pending, it.at.fmt(zone))) }
            if (day != null && day.status != DayStatus.WORKING) {
                Text(stringResource(R.string.shell_day_status, statusLabel(day.status)))
            }
            state.plan?.let { plan ->
                Text(
                    stringResource(
                        R.string.shell_plan,
                        plan.geofence.name,
                        stringResource(if (plan.wifiCallback) R.string.shell_on else R.string.shell_off),
                        plan.reason.name,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsCard(
    mode: DetectionMode,
    exitTracking: Boolean,
    onMode: (DetectionMode) -> Unit,
    onExitTracking: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.shell_settings_title), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DetectionMode.entries.forEach { m ->
                    FilterChip(selected = m == mode, onClick = { onMode(m) }, label = { Text(modeLabel(m)) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.shell_exit_tracking), Modifier.weight(1f))
                Switch(checked = exitTracking, onCheckedChange = onExitTracking)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SimulateCard(
    signalTime: String,
    message: ShellMessage?,
    workplaceCount: Int,
    ssid: String,
    onTime: (String) -> Unit,
    onSimulate: (SimAction, String) -> Unit,
    onSettle: () -> Unit,
    onMissed: () -> Unit,
    onReset: () -> Unit,
) {
    val demoName = stringResource(R.string.shell_demo_workplace)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.shell_sim_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.shell_sim_hint), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.shell_workplaces, workplaceCount, ssid), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = signalTime,
                onValueChange = onTime,
                label = { Text(stringResource(R.string.shell_sim_time)) },
                isError = message is ShellMessage.BadTime,
                supportingText = if (message is ShellMessage.BadTime) {
                    { Text(stringResource(R.string.shell_sim_time_error)) }
                } else {
                    null
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SimAction.entries.forEach { action ->
                    FilledTonalButton(onClick = { onSimulate(action, demoName) }) { Text(simLabel(action)) }
                }
            }
            HorizontalDivider()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSettle) { Text(stringResource(R.string.shell_settle)) }
                OutlinedButton(onClick = onMissed) { Text(stringResource(R.string.shell_missed)) }
                OutlinedButton(onClick = onReset) { Text(stringResource(R.string.shell_reset)) }
            }
            if (message is ShellMessage.Result) {
                Text(
                    stringResource(R.string.shell_last_result, message.text),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun EventRow(event: RawEvent, zone: ZoneId) {
    val delaySeconds = Duration.between(event.at, event.receivedAt).seconds
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(
                R.string.shell_event_line,
                event.at.atZone(zone).format(SECONDS),
                event.type.name,
                stringResource(if (event.accepted) R.string.shell_event_accepted else R.string.shell_event_ignored),
            ),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(event.reason, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        if (delaySeconds > 0) {
            Text(stringResource(R.string.shell_event_delay, delaySeconds), style = MaterialTheme.typography.labelSmall)
        }
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun statusLabel(status: DayStatus): String = stringResource(
    when (status) {
        DayStatus.WORKING -> R.string.status_working
        DayStatus.DAY_OFF -> R.string.status_day_off
        DayStatus.WFH -> R.string.status_wfh
        DayStatus.LEAVE -> R.string.status_leave
        DayStatus.HOLIDAY -> R.string.status_holiday
        DayStatus.WEEKEND -> R.string.status_weekend
    },
)

@Composable
private fun sourceLabel(source: SignalSource?): String = stringResource(
    when (source) {
        SignalSource.GEOFENCE -> R.string.source_geofence
        SignalSource.WIFI -> R.string.source_wifi
        SignalSource.MANUAL, null -> R.string.source_manual
    },
)

@Composable
private fun modeLabel(mode: DetectionMode): String = stringResource(
    when (mode) {
        DetectionMode.GEOFENCE_OR_WIFI -> R.string.mode_or
        DetectionMode.GEOFENCE_AND_WIFI -> R.string.mode_and
        DetectionMode.WIFI_ONLY -> R.string.mode_wifi
        DetectionMode.GEOFENCE_ONLY -> R.string.mode_geofence
    },
)

@Composable
private fun simLabel(action: SimAction): String = stringResource(
    when (action) {
        SimAction.WIFI_CONNECTED -> R.string.sim_wifi
        SimAction.WIFI_LOST -> R.string.sim_wifi_lost
        SimAction.GEOFENCE_ENTER -> R.string.sim_enter
        SimAction.GEOFENCE_DWELL -> R.string.sim_dwell
        SimAction.GEOFENCE_EXIT -> R.string.sim_exit
        SimAction.MANUAL -> R.string.sim_manual
    },
)

private fun Instant.fmt(zone: ZoneId): String = atZone(zone).format(TIME)

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val SECONDS: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
