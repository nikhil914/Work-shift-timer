package com.arrivelog.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.arrivelog.R
import com.arrivelog.domain.day.DayEdit
import com.arrivelog.domain.day.DayEditor
import com.arrivelog.domain.day.EditProblem
import com.arrivelog.domain.model.DayStatus
import com.arrivelog.domain.usecase.EditResult
import com.arrivelog.notify.EditFocus
import com.arrivelog.ui.common.TimePickerDialog
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private enum class Picking { ARRIVAL, EXIT }

private val EDITABLE_STATUSES = listOf(DayStatus.WORKING, DayStatus.DAY_OFF, DayStatus.WFH, DayStatus.LEAVE, DayStatus.HOLIDAY)

/** Spec section 4: change arrival / exit / status / note, with the auto-detected original shown. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditDaySheet(
    target: EditTarget,
    defaultZone: ZoneId,
    error: EditResult.Invalid?,
    onDismiss: () -> Unit,
    onSave: (DayEdit) -> Unit,
) {
    val record = target.record
    val zone = record.zoneId ?: defaultZone
    val date = target.date
    var arrival by rememberSaveable(target) { mutableStateOf(record.arrivalAt?.toEpochMilli()) }
    var exit by rememberSaveable(target) { mutableStateOf(record.exitAt?.toEpochMilli()) }
    var status by rememberSaveable(target) { mutableStateOf(record.status) }
    var note by rememberSaveable(target) { mutableStateOf(record.note.orEmpty()) }
    var picking by remember { mutableStateOf<Picking?>(null) }

    val edit = DayEdit(arrival?.let(Instant::ofEpochMilli), exit?.let(Instant::ofEpochMilli), status, note)
    val problems = DayEditor.validate(edit) + error?.problems.orEmpty()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(date.format(TITLE), style = MaterialTheme.typography.titleLarge)

            Text(stringResource(R.string.edit_status), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EDITABLE_STATUSES.forEach { s ->
                    FilterChip(
                        selected = status == s,
                        onClick = {
                            status = s
                            if (s != DayStatus.WORKING) {
                                arrival = null
                                exit = null
                            }
                        },
                        label = { Text(statusLabel(s)) },
                    )
                }
            }
            if (target.focus == EditFocus.STATUS && status == DayStatus.WORKING) {
                Text(stringResource(R.string.edit_status_hint), style = MaterialTheme.typography.bodySmall)
            }

            if (status == DayStatus.WORKING) {
                TimeField(
                    label = stringResource(R.string.edit_arrival),
                    value = arrival?.let { Instant.ofEpochMilli(it).fmt(zone) },
                    onPick = { picking = Picking.ARRIVAL },
                    onClear = { arrival = null; exit = null },
                )
                TimeField(
                    label = stringResource(R.string.edit_exit),
                    value = exit?.let { Instant.ofEpochMilli(it).fmt(zone) },
                    onPick = { picking = Picking.EXIT },
                    onClear = { exit = null },
                    enabled = arrival != null,
                )
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(200) },
                label = { Text(stringResource(R.string.edit_note)) },
                modifier = Modifier.fillMaxWidth(),
            )

            // Audit trail: what was detected automatically, if it differs from what is shown now.
            record.originalArrivalAt?.let { original ->
                if (record.arrivalEdited || edit.arrivalAt != original) {
                    Text(stringResource(R.string.edit_audit_arrival, original.fmt(zone)), style = MaterialTheme.typography.bodySmall)
                }
            }
            record.originalExitAt?.let { original ->
                if (record.exitEdited || edit.exitAt != original) {
                    Text(stringResource(R.string.edit_audit_exit, original.fmt(zone)), style = MaterialTheme.typography.bodySmall)
                }
            }

            problems.forEach { p ->
                Text(stringResource(problemText(p)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Button(onClick = { onSave(edit) }, enabled = DayEditor.validate(edit).isEmpty()) { Text(stringResource(R.string.action_save)) }
            }
        }
    }

    picking?.let { which ->
        val current = when (which) {
            Picking.ARRIVAL -> arrival
            Picking.EXIT -> exit
        }?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() }
            ?: if (which == Picking.ARRIVAL) LocalTime.of(8, 0) else LocalTime.of(17, 0)
        TimePickerDialog(
            initial = current,
            title = stringResource(if (which == Picking.ARRIVAL) R.string.edit_arrival else R.string.edit_exit),
            onDismiss = { picking = null },
            onConfirm = { t ->
                val millis = ZonedDateTime.of(date, t, zone).toInstant().toEpochMilli()
                if (which == Picking.ARRIVAL) arrival = millis else exit = millis
                picking = null
            },
        )
    }
}

@Composable
private fun TimeField(label: String, value: String?, onPick: () -> Unit, onClear: () -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = onPick, enabled = enabled) {
            Text(value ?: stringResource(R.string.edit_set), style = MaterialTheme.typography.titleLarge)
        }
        if (value != null) TextButton(onClick = onClear) { Text(stringResource(R.string.edit_clear)) }
    }
}

private fun problemText(p: EditProblem): Int = when (p) {
    EditProblem.EXIT_WITHOUT_ARRIVAL -> R.string.edit_err_exit_without_arrival
    EditProblem.EXIT_BEFORE_ARRIVAL -> R.string.edit_err_exit_before
    EditProblem.TIMES_ON_NON_WORKING_DAY -> R.string.edit_err_times_non_working
}

private val TITLE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy")
