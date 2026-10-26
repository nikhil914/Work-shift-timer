package com.arrivelog.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.arrivelog.R
import com.arrivelog.ui.common.StepScaffold
import com.arrivelog.ui.nav.SetupStep

/** Every wizard step, reopenable on its own (spec: "also editable later in Settings"). */
@Composable
fun SettingsScreen(onOpen: (SetupStep) -> Unit, onHealth: () -> Unit, onExport: () -> Unit, onDebug: () -> Unit, onDevTools: () -> Unit, onBack: () -> Unit) {
    StepScaffold(
        title = stringResource(R.string.settings_title),
        step = null,
        wizard = false,
        primaryEnabled = true,
        onPrimary = onBack,
        navigationAction = { TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) } },
    ) {
        SetupStep.entries.forEach { step ->
            item {
                Column(
                    Modifier.fillMaxWidth().clickable { onOpen(step) }.padding(vertical = 12.dp),
                ) {
                    Text(stringResource(step.title()), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(step.subtitle()), style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
        item {
            Column(Modifier.fillMaxWidth().clickable(onClick = onExport).padding(vertical = 12.dp)) {
                Text(stringResource(R.string.export_title), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.settings_export_sub), style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
        }
        item {
            Column(Modifier.fillMaxWidth().clickable(onClick = onHealth).padding(vertical = 12.dp)) {
                Text(stringResource(R.string.health_title), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.settings_health_sub), style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
        }
        item {
            Column(Modifier.fillMaxWidth().clickable(onClick = onDebug).padding(vertical = 12.dp)) {
                Text(stringResource(R.string.debug_title), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.debug_sub), style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
        }
        item {
            Column(Modifier.fillMaxWidth().clickable(onClick = onDevTools).padding(vertical = 12.dp)) {
                Text(stringResource(R.string.devtools_title), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.devtools_sub), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

fun SetupStep.title(): Int = when (this) {
    SetupStep.WORKPLACES -> R.string.workplaces_title
    SetupStep.WIFI -> R.string.wifi_title
    SetupStep.MODE -> R.string.mode_title
    SetupStep.SCHEDULE -> R.string.schedule_title
    SetupStep.PERMISSIONS -> R.string.perm_title
    SetupStep.TEST -> R.string.test_title
}

private fun SetupStep.subtitle(): Int = when (this) {
    SetupStep.WORKPLACES -> R.string.settings_workplaces_sub
    SetupStep.WIFI -> R.string.settings_wifi_sub
    SetupStep.MODE -> R.string.settings_mode_sub
    SetupStep.SCHEDULE -> R.string.settings_schedule_sub
    SetupStep.PERMISSIONS -> R.string.settings_perm_sub
    SetupStep.TEST -> R.string.settings_test_sub
}
