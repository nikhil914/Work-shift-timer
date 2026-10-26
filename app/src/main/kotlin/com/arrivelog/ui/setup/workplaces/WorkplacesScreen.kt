package com.arrivelog.ui.setup.workplaces

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arrivelog.R
import com.arrivelog.domain.model.Workplace
import com.arrivelog.ui.common.SectionCard
import com.arrivelog.ui.common.StepScaffold
import com.arrivelog.ui.nav.SetupStep

@Composable
fun WorkplacesScreen(
    wizard: Boolean,
    onEdit: (Long) -> Unit,
    onNext: () -> Unit,
    viewModel: WorkplacesViewModel = hiltViewModel(),
) {
    val workplaces by viewModel.workplaces.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf<Workplace?>(null) }
    val list = workplaces.orEmpty()

    StepScaffold(
        title = stringResource(R.string.workplaces_title),
        step = SetupStep.WORKPLACES,
        wizard = wizard,
        primaryEnabled = list.isNotEmpty(),
        onPrimary = onNext,
    ) {
        item { Text(stringResource(R.string.workplaces_intro), style = MaterialTheme.typography.bodyMedium) }
        items(list, key = { it.id }) { w ->
            SectionCard {
                Text(w.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.workplaces_row_detail, w.radiusMeters, "%.5f".format(w.latitude), "%.5f".format(w.longitude)),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    if (w.ssids.isEmpty()) {
                        stringResource(R.string.workplaces_row_no_wifi)
                    } else {
                        pluralStringResource(R.plurals.workplaces_row_wifi, w.ssids.size, w.ssids.joinToString())
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { onEdit(w.id) }) { Text(stringResource(R.string.action_edit)) }
                    TextButton(onClick = { confirmDelete = w }) { Text(stringResource(R.string.action_delete)) }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (list.size < Workplace.MAX_WORKPLACES) {
                    OutlinedButton(onClick = { onEdit(0L) }, modifier = Modifier) {
                        Text(stringResource(if (list.isEmpty()) R.string.workplaces_add_first else R.string.workplaces_add))
                    }
                } else {
                    Text(stringResource(R.string.workplaces_max, Workplace.MAX_WORKPLACES), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    confirmDelete?.let { w ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.workplaces_delete_title, w.name)) },
            text = { Text(stringResource(R.string.workplaces_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(w.id)
                    confirmDelete = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
