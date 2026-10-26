package com.arrivelog.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.arrivelog.domain.home.DayBadge
import com.arrivelog.domain.home.DaySummary
import com.arrivelog.domain.home.WeekGroup
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.ZoneId

/** An open edit sheet: the day plus how it was opened. */
private data class OpenSheet(val summary: DaySummary, val request: EditRequest)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onSettings: () -> Unit,
    editRequests: StateFlow<EditRequest?>,
    onEditRequestHandled: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val request by editRequests.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf<OpenSheet?>(null) }
    val scope = rememberCoroutineScope()

    fun open(req: EditRequest) {
        scope.launch { sheet = OpenSheet(viewModel.summaryOf(req.date), req) }
    }

    // Notification taps ("Edit time", "Pick time", "Day off / WFH / Leave").
    LaunchedEffect(request, state.loading) {
        val req = request ?: return@LaunchedEffect
        if (state.loading) return@LaunchedEffect
        open(req)
        onEditRequestHandled()
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
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.today?.let { today ->
                item(key = "today") {
                    TodayCard(
                        summary = today,
                        zone = today.day?.zoneId ?: state.zone,
                        onLogNow = viewModel::logNow,
                        onEdit = { open(EditRequest(today.date, EditFocus.NONE)) },
                    )
                }
            }
            if (state.attentionCount > 0) {
                item(key = "attention") { AttentionBanner(state.attentionCount) }
            }
            state.weeks.forEach { week ->
                item(key = "week-${week.weekStart}") { WeekHeader(week) }
                items(week.days, key = { it.date.toString() }) { day ->
                    DayRow(day, day.day?.zoneId ?: state.zone, onClick = { open(EditRequest(day.date, EditFocus.NONE)) })
                }
            }
            if (state.canShowOlder) {
                item(key = "older") {
                    OutlinedButton(onClick = viewModel::showOlderWeeks, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.home_show_older))
                    }
                }
            }
        }
    }

    sheet?.let { current ->
        EditDaySheet(
            summary = current.summary,
            request = current.request,
            zone = current.summary.day?.zoneId ?: state.zone,
            onDismiss = { sheet = null },
            onSave = { edit, onResult -> viewModel.save(current.summary.date, edit, onResult) },
        )
    }
}

@Composable
private fun TodayCard(summary: DaySummary, zone: ZoneId, onLogNow: () -> Unit, onEdit: () -> Unit) {
    val day = summary.day
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(summary.date.format(DAY_LONG), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = day?.arrivalAt?.hhmm(zone) ?: stringResource(R.string.home_no_time),
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(12.dp))
                SourceGlyph(day?.arrivalSource, Modifier.alpha(0.9f))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BadgeChip(summary.badge)
                if (day?.editedManually == true) Text(stringResource(R.string.home_edited), style = MaterialTheme.typography.labelMedium)
            }
            if (summary.atWorkNow) {
                summary.worked?.let { Text(stringResource(R.string.home_at_work_for, durationText(it)), style = MaterialTheme.typography.bodyLarge) }
            }
            day?.exitAt?.let { exit ->
                val worked = summary.worked?.let { durationText(it) }
                Text(
                    if (worked != null) stringResource(R.string.home_left_at_worked, exit.hhmm(zone), worked) else stringResource(R.string.home_left_at, exit.hhmm(zone)),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (day?.arrivalAt == null && summary.badge !is DayBadge.Off) {
                Button(onClick = onLogNow) { Text(stringResource(R.string.home_log_now)) }
            }
        }
    }
}

@Composable
private fun AttentionBanner(count: Int) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
        Text(
            pluralStringResource(R.plurals.home_attention, count, count),
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun WeekHeader(week: WeekGroup) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.home_week_of, week.weekStart.format(WEEK_LABEL)),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        if (!week.worked.isZero) Text(durationText(week.worked), style = MaterialTheme.typography.labelLarge)
    }
    HorizontalDivider()
}

@Composable
private fun DayRow(summary: DaySummary, zone: ZoneId, onClick: () -> Unit) {
    val day = summary.day
    val dim = if (summary.isOffDay) 0.45f else 1f
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp).alpha(dim),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(summary.date.format(DAY_SHORT), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(64.dp))
        when {
            day?.arrivalAt != null -> {
                Text(day.arrivalAt?.hhmm(zone).orEmpty(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text("–", style = MaterialTheme.typography.bodyLarge)
                Text(day.exitAt?.hhmm(zone) ?: stringResource(R.string.home_open_exit), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.weight(1f))
                summary.worked?.let { Text(durationText(it), style = MaterialTheme.typography.bodyMedium) }
                SourceGlyph(day.arrivalSource)
                if (day.editedManually) Text(stringResource(R.string.home_edited_short), style = MaterialTheme.typography.labelSmall)
            }
            summary.needsAttention -> {
                Text(
                    stringResource(R.string.home_needs_attention),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            else -> {
                Text(badgeText(summary.badge), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
    }
}
