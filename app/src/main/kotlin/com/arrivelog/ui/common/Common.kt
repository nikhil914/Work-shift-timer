package com.arrivelog.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.arrivelog.R
import com.arrivelog.ui.nav.SetupStep
import java.time.LocalTime
import java.time.format.DateTimeFormatter

val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Shared frame for every setup step: title, wizard progress, scrollable content, and one primary
 * button pinned at the bottom ("Next" in the wizard, "Done" from Settings).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StepScaffold(
    title: String,
    step: SetupStep?,
    wizard: Boolean,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    primaryLabel: String? = null,
    navigationAction: @Composable () -> Unit = {},
    actions: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text(title) }, navigationIcon = navigationAction, actions = { actions() })
                if (wizard && step != null) {
                    LinearProgressIndicator(
                        progress = { (step.ordinal + 1f) / SetupStep.entries.size },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (wizard && step != null) {
                        Text(
                            stringResource(R.string.setup_step_of, step.ordinal + 1, SetupStep.entries.size),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = onPrimary, enabled = primaryEnabled) {
                        Text(primaryLabel ?: stringResource(if (wizard) R.string.action_next else R.string.action_done))
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 12.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    tone: CardTone = CardTone.Normal,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = when (tone) {
        CardTone.Normal -> CardDefaults.cardColors()
        CardTone.Warning -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        CardTone.Good -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    }
    Card(modifier.fillMaxWidth(), colors = colors) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

enum class CardTone { Normal, Warning, Good }

@Composable
fun StatusDot(ok: Boolean) {
    Text(
        text = if (ok) "✓" else "!",
        color = if (ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.width(20.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(
    initial: LocalTime,
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit,
) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
