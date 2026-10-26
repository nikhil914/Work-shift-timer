package com.arrivelog.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.arrivelog.R
import com.arrivelog.domain.home.DaySummary
import com.arrivelog.domain.model.DayStatus
import com.arrivelog.domain.model.SignalSource
import com.arrivelog.domain.usecase.DayEdit
import com.arrivelog.domain.usecase.DayEditor
import com.arrivelog.domain.usecase.EditError
import com.arrivelog.domain.usecase.EditResult
import com.arrivelog.ui.common.TimePickerDialog
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

private enum class Picker { ARRIVAL, EXIT }

/**
 * Change arrival / exit, status and note for one day. The audit line under each time shows what
 * was detected automatically when it has since been edited (spec section 4).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditDaySheet(
    summary: DaySummary,
    request: EditRequest,
    zone: ZoneId,
    onDismiss: () -> Unit,
    onSave: (DayEdit, (EditResult) -> Unit) -> Unit,
) {
    val day = summary.day
    val toLocal = { t: Instant? -> t?.atZone(zone)?.toLocalTime()?.withSecond(0)?.withNano(0) }
    var arrival by remember { mutableStateOf(toLocal(day?.arrivalAt)) }
    var exit by remember { mutableStateOf(toLocal(day?.exitAt)) }
    var status by remember {
        mutableStateOf(day?.status?.takeIf { it in DayEditor.SELECTABLE_STATUSES } ?: DayStatus.WORKING)
    }
    var note by remember { mutableStateOf(day?.note.orEmpty()) }
    var error by remember { mutableStateOf<EditError?>(null) }
    var picker by remember { mutableStateOf(if (request.focus == EditFocus.ARRIVAL) Picker.ARRIVAL else null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(summary.date.format(DAY_LONG), style = MaterialTheme.typography.titleLarge)

            TimeRow(
                label = stringResource(R.string.edit_arrival),
                time = arrival,
                onPick = { picker = Picker.ARRIVAL },
                onClear = { arrival = null; exit = null },
            )
            AuditLine(
                original = day?.originalArrivalAt,
                current = day?.arrivalAt,
                edited = day?.arrivalEdited == true,
                source = day?.arrivalSource,
                zone = zone,
            )

            TimeRow(
                label = stringResource(R.string.edit_exit),
                time = exit,
                enabled = arrival != null,
                onPick = { picker = Picker.EXIT },
                onClear = { exit = null },
            )
            AuditLine(
                original = day?.originalExitAt,
                current = day?.exitAt,
                edited = day?.exitEdited == true,
                source = day?.exitSource,
                zone = zone,
            )

            Text(
                stringResource(if (request.focus == EditFocus.STATUS) R.string.edit_status_prompt else R.string.edit_status),
                style = MaterialTheme.typography.titleSmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DayEditor.SELECTABLE_STATUSES.forEach { option ->
                    FilterChip(selected = status == option, onClick = { status = option }, label = { Text(statusText(option)) })
                }
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(MAX_NOTE) },
                label = { Text(stringResource(R.string.edit_note)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )

            error?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error) }

            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Spacer(Modifier.padding(4.dp))
                Button(onClick = {
                    onSave(DayEdit(arrival, exit, status, note)) { result ->
                        when (result) {
                            is EditResult.Invalid -> error = result.error
                            is EditResult.Saved, EditResult.Unchanged -> onDismiss()
                        }
                    }
                }) { Text(stringResource(R.string.action_save)) }
            }
        }
    }

    picker?.let { which ->
        val initial = when (which) {
            Picker.ARRIVAL -> arrival ?: request.suggestedArrival ?: toLocal(summary.expectedStart) ?: LocalTime.now(zone)
            Picker.EXIT -> exit ?: LocalTime.now(zone).withSecond(0).withNano(0)
        }
        TimePickerDialog(
            initial = initial,
            title = stringResource(if (which == Picker.ARRIVAL) R.string.edit_pick_arrival else R.string.edit_pick_exit),
            onDismiss = { picker = null },
            onConfirm = { time ->
                if (which == Picker.ARRIVAL) {
                    arrival = time
                    // Picking an arrival on a day marked off means you worked after all.
                    if (day?.arrivalAt == null && status != DayStatus.WORKING) status = DayStatus.WORKING
                } else {
                    exit = time
                }
                error = null
                picker = null
            },
        )
    }
}

@Composable
private fun TimeRow(label: String, time: LocalTime?, onPick: () -> Unit, onClear: () -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onPick, enabled = enabled) {
            Text(time?.format(TIME_24H) ?: stringResource(R.string.edit_set_time))
        }
        if (time != null) TextButton(onClick = onClear) { Text(stringResource(R.string.edit_clear)) }
    }
}

@Composable
private fun AuditLine(original: Instant?, current: Instant?, edited: Boolean, source: SignalSource?, zone: ZoneId) {
    val text = when {
        edited && original != null && source != null ->
            stringResource(R.string.edit_audit_edited, original.hhmm(zone), sourceName(source))
        current != null && source != null -> stringResource(R.string.edit_audit_source, sourceName(source))
        else -> return
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun errorText(error: EditError): String = stringResource(
    when (error) {
        EditError.EXIT_WITHOUT_ARRIVAL -> R.string.edit_error_exit_without_arrival
        EditError.EXIT_NOT_AFTER_ARRIVAL -> R.string.edit_error_exit_before_arrival
        EditError.TIME_IN_FUTURE -> R.string.edit_error_future
        EditError.STATUS_NOT_SELECTABLE -> R.string.edit_error_status
    },
)

private const val MAX_NOTE = 500
