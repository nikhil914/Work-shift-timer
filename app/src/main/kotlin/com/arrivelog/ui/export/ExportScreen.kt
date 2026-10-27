package com.arrivelog.ui.export

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arrivelog.R
import com.arrivelog.ui.common.CardTone
import com.arrivelog.ui.common.SectionCard
import com.arrivelog.ui.common.StepScaffold
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Spec section 8: export a date range, and backup / restore everything to one JSON file. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExportScreen(
    onBack: () -> Unit,
    exportViewModel: ExportViewModel = hiltViewModel(),
    backupViewModel: BackupViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val s by exportViewModel.state.collectAsStateWithLifecycle()
    val busy by backupViewModel.busy.collectAsStateWithLifecycle()
    val message by backupViewModel.message.collectAsStateWithLifecycle()
    val pending by backupViewModel.pending.collectAsStateWithLifecycle()
    var pickingRange by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { exportViewModel.share.collect { context.startActivity(it) } }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(backupViewModel::saveTo)
    }
    // Some file managers label JSON as text/plain or octet-stream, so accept those too.
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(backupViewModel::open)
    }

    StepScaffold(
        title = stringResource(R.string.export_title),
        step = null,
        wizard = false,
        primaryEnabled = !s.working,
        onPrimary = exportViewModel::export,
        primaryLabel = stringResource(R.string.export_action, s.format.extension.uppercase()),
        navigationAction = { TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) } },
    ) {
        item {
            SectionCard {
                Text(stringResource(R.string.export_range), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RangePreset.entries.forEach { preset ->
                        FilterChip(
                            selected = s.preset == preset,
                            onClick = {
                                if (preset == RangePreset.CUSTOM) pickingRange = true else exportViewModel.setPreset(preset)
                            },
                            label = { Text(stringResource(preset.label())) },
                        )
                    }
                }
                Text(
                    stringResource(R.string.export_range_value, s.from.format(DAY), s.to.format(DAY)),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(stringResource(R.string.export_format), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExportFormat.entries.forEach { f ->
                        FilterChip(
                            selected = s.format == f,
                            onClick = { exportViewModel.setFormat(f) },
                            label = { Text(stringResource(if (f == ExportFormat.XLSX) R.string.export_xlsx else R.string.export_csv)) },
                        )
                    }
                }
                Text(stringResource(R.string.export_hint), style = MaterialTheme.typography.bodySmall)
                if (s.failed) Text(stringResource(R.string.export_failed), color = MaterialTheme.colorScheme.error)
            }
        }
        item {
            SectionCard {
                Text(stringResource(R.string.backup_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.backup_body), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { saveLauncher.launch(backupViewModel.suggestedName()) }, enabled = !busy) {
                        Text(stringResource(R.string.backup_save))
                    }
                    OutlinedButton(
                        onClick = { openLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                        enabled = !busy,
                    ) { Text(stringResource(R.string.backup_restore)) }
                }
            }
        }
        message?.let { m ->
            item {
                SectionCard(tone = if (m is BackupMessage.Saved || m is BackupMessage.Restored) CardTone.Good else CardTone.Warning) {
                    Text(
                        when (m) {
                            is BackupMessage.Saved -> pluralStringResource(R.plurals.backup_saved, m.days, m.days)
                            is BackupMessage.Restored -> pluralStringResource(R.plurals.backup_restored, m.days, m.days)
                            BackupMessage.Invalid -> stringResource(R.string.backup_invalid)
                            BackupMessage.Failed -> stringResource(R.string.backup_failed)
                        },
                    )
                    TextButton(onClick = backupViewModel::dismissMessage) { Text(stringResource(R.string.action_ok)) }
                }
            }
        }
    }

    pending?.let { p ->
        AlertDialog(
            onDismissRequest = backupViewModel::cancelRestore,
            title = { Text(stringResource(R.string.backup_confirm_title)) },
            text = { Text(stringResource(R.string.backup_confirm_body, p.days, p.workplaces)) },
            confirmButton = { TextButton(onClick = backupViewModel::confirmRestore) { Text(stringResource(R.string.backup_restore)) } },
            dismissButton = { TextButton(onClick = backupViewModel::cancelRestore) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (pickingRange) {
        RangeDialog(
            from = s.from,
            to = s.to,
            onDismiss = { pickingRange = false },
            onConfirm = { from, to ->
                exportViewModel.setCustomRange(from, to)
                pickingRange = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeDialog(from: LocalDate, to: LocalDate, onDismiss: () -> Unit, onConfirm: (LocalDate, LocalDate) -> Unit) {
    // The Material date pickers work in UTC milliseconds at midnight.
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = from.toUtcMillis(),
        initialSelectedEndDateMillis = to.toUtcMillis(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val start = state.selectedStartDateMillis?.toUtcDate()
                    if (start != null) onConfirm(start, state.selectedEndDateMillis?.toUtcDate() ?: start)
                },
                enabled = state.selectedStartDateMillis != null,
            ) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        DateRangePicker(state = state, modifier = Modifier.fillMaxWidth())
    }
}

private fun RangePreset.label(): Int = when (this) {
    RangePreset.THIS_MONTH -> R.string.export_this_month
    RangePreset.LAST_MONTH -> R.string.export_last_month
    RangePreset.LAST_3_MONTHS -> R.string.export_last_3_months
    RangePreset.THIS_YEAR -> R.string.export_this_year
    RangePreset.CUSTOM -> R.string.export_custom
}

private fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toUtcDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")
