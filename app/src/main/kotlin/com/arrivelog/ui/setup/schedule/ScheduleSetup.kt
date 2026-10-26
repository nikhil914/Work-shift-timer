package com.arrivelog.ui.setup.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.arrivelog.R
import com.arrivelog.domain.model.AppSettings
import com.arrivelog.domain.model.DayStatus
import com.arrivelog.domain.model.ScheduleProfile
import com.arrivelog.domain.model.ScheduleSettings
import com.arrivelog.domain.repository.SettingsRepository
import com.arrivelog.domain.schedule.ScheduleEditor
import com.arrivelog.ui.common.HHMM
import com.arrivelog.ui.common.SectionCard
import com.arrivelog.ui.common.StepScaffold
import com.arrivelog.ui.nav.SetupStep
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import javax.inject.Inject

@HiltViewModel
class ScheduleViewModel @Inject constructor(private val repo: SettingsRepository) : ViewModel() {
    val settings: StateFlow<AppSettings> = repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private fun edit(f: (ScheduleSettings) -> ScheduleSettings) = viewModelScope.launch {
        repo.update { it.copy(schedule = f(it.schedule)) }
    }

    fun assign(day: DayOfWeek, profileId: String?) = edit { ScheduleEditor.assign(it, day, profileId) }
    fun deleteProfile(id: String) = edit { ScheduleEditor.deleteProfile(it, id) }
    fun addDaysOff(from: LocalDate, to: LocalDate, status: DayStatus) = edit { ScheduleEditor.addDayOffRange(it, from, to, status) }
    fun removeDayOff(date: LocalDate) = edit { ScheduleEditor.removeDayOff(it, date) }
}

/** UAE week first: the weekly list starts on Monday but the picker labels use the device locale. */
private val WEEK = listOf(
    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY,
)
private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    wizard: Boolean,
    onEditProfile: (String) -> Unit,
    onNext: () -> Unit,
    viewModel: ScheduleViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val schedule = settings.schedule
    var addingDaysOff by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<ScheduleProfile?>(null) }

    StepScaffold(
        title = stringResource(R.string.schedule_title),
        step = SetupStep.SCHEDULE,
        wizard = wizard,
        primaryEnabled = schedule.assignments.isNotEmpty(),
        onPrimary = onNext,
    ) {
        item { Text(stringResource(R.string.schedule_intro), style = MaterialTheme.typography.bodyMedium) }
        item {
            SectionCard {
                Text(stringResource(R.string.schedule_week), style = MaterialTheme.typography.titleMedium)
                WEEK.forEach { day ->
                    DayRow(day, schedule, onAssign = { viewModel.assign(day, it) })
                }
                if (schedule.assignments.isEmpty()) {
                    Text(stringResource(R.string.schedule_need_one_day), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Text(stringResource(R.string.schedule_profiles), style = MaterialTheme.typography.titleMedium) }
        items(schedule.profiles, key = { it.id }) { p ->
            SectionCard {
                Text(p.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(
                        R.string.schedule_profile_line,
                        p.entryWindowStart.format(HHMM),
                        p.entryWindowEnd.format(HHMM),
                        p.expectedStart.format(HHMM),
                        p.expectedEnd.format(HHMM),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onEditProfile(p.id) }) { Text(stringResource(R.string.action_edit)) }
                    if (!ScheduleEditor.isBuiltIn(p.id)) {
                        TextButton(onClick = { confirmDelete = p }) { Text(stringResource(R.string.action_delete)) }
                    }
                }
            }
        }
        item { OutlinedButton(onClick = { onEditProfile("") }) { Text(stringResource(R.string.schedule_add_profile)) } }

        item { Text(stringResource(R.string.schedule_days_off), style = MaterialTheme.typography.titleMedium) }
        val upcoming = schedule.daysOff.toSortedMap().entries.toList()
        if (upcoming.isEmpty()) {
            item { Text(stringResource(R.string.schedule_days_off_empty), style = MaterialTheme.typography.bodySmall) }
        }
        items(upcoming, key = { it.key.toString() }) { (date, status) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(date.format(DATE), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(if (status == DayStatus.HOLIDAY) R.string.status_holiday else R.string.status_leave),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = { viewModel.removeDayOff(date) }) { Text(stringResource(R.string.action_remove)) }
            }
        }
        item { OutlinedButton(onClick = { addingDaysOff = true }) { Text(stringResource(R.string.schedule_add_days_off)) } }
    }

    if (addingDaysOff) {
        DaysOffDialog(
            onDismiss = { addingDaysOff = false },
            onConfirm = { from, to, status ->
                viewModel.addDaysOff(from, to, status)
                addingDaysOff = false
            },
        )
    }
    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.schedule_delete_title, p.name)) },
            text = { Text(stringResource(R.string.schedule_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteProfile(p.id)
                    confirmDelete = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun DayRow(day: DayOfWeek, schedule: ScheduleSettings, onAssign: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = schedule.profile(schedule.assignments[day])
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        val locale = LocalConfiguration.current.locales[0]
        Text(day.getDisplayName(TextStyle.FULL, locale), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Box {
            TextButton(onClick = { open = true }) {
                Text(current?.name ?: stringResource(R.string.schedule_off))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                schedule.profiles.forEach { p ->
                    DropdownMenuItem(text = { Text(p.name) }, onClick = { onAssign(p.id); open = false })
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.schedule_off)) }, onClick = { onAssign(null); open = false })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DaysOffDialog(onDismiss: () -> Unit, onConfirm: (LocalDate, LocalDate, DayStatus) -> Unit) {
    val state = rememberDateRangePickerState()
    var status by remember { mutableStateOf(DayStatus.HOLIDAY) }
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            val start = state.selectedStartDateMillis
            TextButton(
                enabled = start != null,
                onClick = {
                    val from = start?.toLocalDate() ?: return@TextButton
                    val to = state.selectedEndDateMillis?.toLocalDate() ?: from
                    onConfirm(from, to, status)
                },
            ) { Text(stringResource(R.string.action_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            FilterChip(status == DayStatus.HOLIDAY, onClick = { status = DayStatus.HOLIDAY }, label = { Text(stringResource(R.string.status_holiday)) })
            FilterChip(status == DayStatus.LEAVE, onClick = { status = DayStatus.LEAVE }, label = { Text(stringResource(R.string.status_leave)) })
        }
        DateRangePicker(state = state, modifier = Modifier.weight(1f))
    }
}

/** Material date pickers report UTC midnight. */
private fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
