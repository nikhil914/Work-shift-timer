package com.arrivelog.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arrivelog.R
import com.arrivelog.domain.day.DayChip
import com.arrivelog.domain.day.DaySummary
import com.arrivelog.domain.day.WeekGroup
import com.arrivelog.domain.model.DayStatus
import com.arrivelog.domain.model.SignalSource
import com.arrivelog.notify.EditFocus
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A request from a notification tap: open the edit sheet for [date]. */
data class EditRequest(val date: LocalDate, val focus: EditFocus)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onSettings: () -> Unit,
    editRequest: EditRequest?,
    onEditRequestHandled: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val editing by viewModel.editing.collectAsStateWithLifecycle()
    val editError by viewModel.editError.collectAsStateWithLifecycle()

    LaunchedEffect(editRequest) {
        editRequest?.let {
            viewModel.openEditor(it.date, it.focus)
            onEditRequestHandled()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = { TextButton(onClick = onSettings) { Text(stringResource(R.string.settings_title)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        ) {
            state.today?.let { today ->
                item(key = "today") {
                    TodayCard(today, state.pendingExitAt, onClick = { viewModel.openEditor(today.date) })
                }
            }
            if (state.needsAttention.isNotEmpty()) {
                item(key = "attention") {
                    AttentionBanner(state.needsAttention, onClick = { viewModel.openEditor(state.needsAttention.first().date) })
                }
            }
            state.weeks.forEach { week ->
                stickyHeader(key = "w-${week.weekStart}") { WeekHeader(week) }
                items(week.days, key = { it.date.toString() }) { day ->
                    DayRow(day, onClick = { viewModel.openEditor(day.date) })
                }
            }
        }
    }

    editing?.let { target ->
        EditDaySheet(
            target = target,
            defaultZone = viewModel.zone,
            error = editError,
            onDismiss = viewModel::closeEditor,
            onSave = { viewModel.save(target.date, it) },
        )
    }
}

@Composable
private fun TodayCard(day: DaySummary, pendingExitAt: Instant?, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(day.date.format(LONG_DATE), style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    day.arrivalAt?.fmt(day.zone) ?: "—",
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                day.arrivalSource?.let {
                    Text("  " + sourceLabel(it), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
                }
            }
            AssistChip(onClick = onClick, label = { Text(chipLabel(day.chip)) })
            day.profileName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            val exitAt = day.exitAt
            when {
                exitAt != null -> Text(stringResource(R.string.home_left_at, exitAt.fmt(day.zone)), style = MaterialTheme.typography.bodyLarge)
                pendingExitAt != null -> Text(stringResource(R.string.home_leaving, pendingExitAt.fmt(day.zone)), style = MaterialTheme.typography.bodyLarge)
            }
            day.worked?.let {
                Text(
                    stringResource(if (day.exitAt == null) R.string.home_at_work_live else R.string.home_worked, formatDuration(it)),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (day.edited) Text(stringResource(R.string.home_edited), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun AttentionBanner(days: List<DaySummary>, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(pluralStringResource(R.plurals.home_attention_title_plural, days.size, days.size), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.home_attention_body, days.first().date.format(SHORT_DATE)), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun WeekHeader(week: WeekGroup) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.home_week_of, week.weekStart.format(SHORT_DATE)), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (!week.worked.isZero) Text(formatDuration(week.worked), style = MaterialTheme.typography.labelLarge)
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun DayRow(day: DaySummary, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp).alpha(if (day.greyed) 0.5f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(96.dp)) {
            Text(day.date.format(ROW_DATE), style = MaterialTheme.typography.bodyLarge)
            if (day.edited) Text(stringResource(R.string.home_edited), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        val arrivalAt = day.arrivalAt
        if (arrivalAt == null) {
            Text(
                chipLabel(day.chip),
                style = MaterialTheme.typography.bodyMedium,
                color = if (day.chip == DayChip.Missing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        } else {
            Text(arrivalAt.fmt(day.zone), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.width(56.dp))
            Text(day.exitAt?.fmt(day.zone) ?: "—", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(56.dp))
            Text(day.worked?.takeIf { day.exitAt != null }?.let { formatDuration(it) } ?: "", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            day.arrivalSource?.let { Text(sourceShort(it), style = MaterialTheme.typography.labelMedium) }
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
fun chipLabel(chip: DayChip): String = when (chip) {
    DayChip.OnTime -> stringResource(R.string.chip_on_time)
    is DayChip.Late -> stringResource(R.string.chip_late, chip.minutes)
    DayChip.Arrived -> stringResource(R.string.chip_arrived)
    DayChip.NotYet -> stringResource(R.string.chip_not_yet)
    DayChip.Missing -> stringResource(R.string.chip_missing)
    is DayChip.Status -> statusLabel(chip.status)
}

@Composable
fun statusLabel(status: DayStatus): String = stringResource(
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
fun sourceLabel(source: SignalSource): String = stringResource(
    when (source) {
        SignalSource.GEOFENCE -> R.string.source_long_geofence
        SignalSource.WIFI -> R.string.source_long_wifi
        SignalSource.MANUAL -> R.string.source_long_manual
    },
)

@Composable
private fun sourceShort(source: SignalSource): String = stringResource(
    when (source) {
        SignalSource.GEOFENCE -> R.string.source_short_geofence
        SignalSource.WIFI -> R.string.source_short_wifi
        SignalSource.MANUAL -> R.string.source_short_manual
    },
)

@Composable
fun formatDuration(d: Duration): String = stringResource(R.string.duration_hm, d.toHours(), d.toMinutes() % 60)

fun Instant.fmt(zone: ZoneId): String = atZone(zone).format(HHMM)

val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val LONG_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM")
private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
private val ROW_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")
