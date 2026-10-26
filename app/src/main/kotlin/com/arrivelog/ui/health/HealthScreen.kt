package com.arrivelog.ui.health

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arrivelog.R
import com.arrivelog.domain.engine.GeofenceArming
import com.arrivelog.domain.health.HealthCheck
import com.arrivelog.domain.health.HealthItem
import com.arrivelog.domain.model.RawEventType
import com.arrivelog.platform.SettingsIntents
import com.arrivelog.ui.common.CardTone
import com.arrivelog.ui.common.SectionCard
import com.arrivelog.ui.common.StatusDot
import com.arrivelog.ui.common.StepScaffold
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Spec section 4: green / red checklist, each red line with a way to fix it. */
@Composable
fun HealthScreen(onPermissions: () -> Unit, onBack: () -> Unit, viewModel: HealthViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val s by viewModel.state.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    fun fix(item: HealthItem) {
        when (item) {
            HealthItem.BACKGROUND_LOCATION, HealthItem.PRECISE_LOCATION, HealthItem.NEARBY_WIFI -> onPermissions()
            HealthItem.LOCATION_ON -> SettingsIntents.launch(context, SettingsIntents.location())
            HealthItem.NOTIFICATIONS -> SettingsIntents.launch(context, SettingsIntents.notifications(context))
            HealthItem.BATTERY_UNRESTRICTED -> SettingsIntents.launch(context, SettingsIntents.batteryOptimizationList())
            HealthItem.GEOFENCES, HealthItem.WIFI_CALLBACK -> viewModel.rearm()
        }
    }

    StepScaffold(
        title = stringResource(R.string.health_title),
        step = null,
        wizard = false,
        primaryEnabled = !s.loading,
        onPrimary = { viewModel.rearm() },
        primaryLabel = stringResource(R.string.health_rearm),
        navigationAction = { TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) } },
    ) {
        if (s.loading) return@StepScaffold
        item {
            SectionCard(tone = if (s.allGood) CardTone.Good else CardTone.Warning) {
                Text(
                    stringResource(if (s.allGood) R.string.health_all_good else R.string.health_needs_fix),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(if (s.allGood) R.string.health_all_good_body else R.string.health_needs_fix_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        item {
            SectionCard {
                s.checks.forEach { check -> CheckRow(check, onFix = { fix(check.item) }) }
            }
        }
        if (viewModel.isSamsung) {
            item {
                SectionCard {
                    Text(stringResource(R.string.health_samsung_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.health_samsung_body), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { SettingsIntents.launch(context, SettingsIntents.appDetails(context)) }) {
                        Text(stringResource(R.string.health_samsung_open))
                    }
                }
            }
        }
        item {
            SectionCard {
                Text(stringResource(R.string.health_details), style = MaterialTheme.typography.titleSmall)
                s.expected?.let { plan ->
                    Text(
                        stringResource(R.string.health_expected, geofenceLabel(plan.geofence), onOff(plan.wifiCallback)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    stringResource(R.string.health_last_registered, s.lastRegisteredAt.fmt(s.zone)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    s.lastEvent?.let { e -> stringResource(R.string.health_last_event, eventLabel(e.type), e.at.fmt(s.zone)) }
                        ?: stringResource(R.string.health_last_event_none),
                    style = MaterialTheme.typography.bodyMedium,
                )
                s.lastError?.let { error ->
                    Text(
                        stringResource(R.string.health_last_error, error, s.lastErrorAt.fmt(s.zone)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun CheckRow(check: HealthCheck, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(check.ok)
        Column(Modifier.weight(1f)) {
            Text(stringResource(check.item.title()), style = MaterialTheme.typography.bodyLarge)
            if (!check.relevant) {
                Text(stringResource(R.string.health_not_used), style = MaterialTheme.typography.bodySmall)
            } else if (!check.ok) {
                Text(stringResource(check.item.problem()), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (!check.ok) TextButton(onClick = onFix) { Text(stringResource(check.item.fixLabel())) }
    }
}

private fun HealthItem.title(): Int = when (this) {
    HealthItem.BACKGROUND_LOCATION -> R.string.health_bg_location
    HealthItem.PRECISE_LOCATION -> R.string.health_precise
    HealthItem.LOCATION_ON -> R.string.health_location_on
    HealthItem.NOTIFICATIONS -> R.string.health_notifications
    HealthItem.NEARBY_WIFI -> R.string.health_nearby_wifi
    HealthItem.BATTERY_UNRESTRICTED -> R.string.health_battery
    HealthItem.GEOFENCES -> R.string.health_geofences
    HealthItem.WIFI_CALLBACK -> R.string.health_wifi_callback
}

private fun HealthItem.problem(): Int = when (this) {
    HealthItem.BACKGROUND_LOCATION -> R.string.health_bg_location_bad
    HealthItem.PRECISE_LOCATION -> R.string.health_precise_bad
    HealthItem.LOCATION_ON -> R.string.health_location_on_bad
    HealthItem.NOTIFICATIONS -> R.string.health_notifications_bad
    HealthItem.NEARBY_WIFI -> R.string.health_nearby_wifi_bad
    HealthItem.BATTERY_UNRESTRICTED -> R.string.health_battery_bad
    HealthItem.GEOFENCES -> R.string.health_geofences_bad
    HealthItem.WIFI_CALLBACK -> R.string.health_wifi_callback_bad
}

private fun HealthItem.fixLabel(): Int = when (this) {
    HealthItem.GEOFENCES, HealthItem.WIFI_CALLBACK -> R.string.health_fix_rearm
    else -> R.string.health_fix
}

@Composable
private fun geofenceLabel(arming: GeofenceArming): String = stringResource(
    when (arming) {
        GeofenceArming.OFF -> R.string.health_geofence_off
        GeofenceArming.ARRIVAL -> R.string.health_geofence_arrival
        GeofenceArming.PRESENCE -> R.string.health_geofence_presence
    },
)

@Composable
private fun onOff(on: Boolean): String = stringResource(if (on) R.string.shell_on else R.string.shell_off)

@Composable
private fun eventLabel(type: RawEventType): String = stringResource(
    when (type) {
        RawEventType.GEOFENCE_ENTER -> R.string.health_event_enter
        RawEventType.GEOFENCE_DWELL -> R.string.health_event_dwell
        RawEventType.GEOFENCE_EXIT -> R.string.health_event_exit
        RawEventType.WIFI_CONNECTED -> R.string.health_event_wifi
        RawEventType.WIFI_LOST -> R.string.health_event_wifi_lost
        RawEventType.WIFI_IN_RANGE -> R.string.health_event_wifi_range
        RawEventType.MANUAL -> R.string.health_event_manual
        RawEventType.SYSTEM -> R.string.health_event_system
    },
)

private fun Instant?.fmt(zone: ZoneId): String = this?.atZone(zone)?.format(WHEN) ?: "—"

private val WHEN: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")
