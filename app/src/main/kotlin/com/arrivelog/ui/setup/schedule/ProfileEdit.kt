package com.arrivelog.ui.setup.schedule

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.arrivelog.R
import com.arrivelog.domain.model.ScheduleProfile
import com.arrivelog.domain.repository.SettingsRepository
import com.arrivelog.domain.schedule.ProfileProblem
import com.arrivelog.domain.schedule.ScheduleEditor
import com.arrivelog.domain.schedule.ScheduleValidator
import com.arrivelog.ui.common.HHMM
import com.arrivelog.ui.common.SectionCard
import com.arrivelog.ui.common.StepScaffold
import com.arrivelog.ui.common.TimePickerDialog
import com.arrivelog.ui.nav.ProfileEditRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalTime
import javax.inject.Inject

enum class ProfileField { ENTRY_START, ENTRY_END, EXPECTED_START, EXPECTED_END }

@HiltViewModel
class ProfileEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: SettingsRepository,
) : ViewModel() {
    private val requestedId = savedStateHandle.toRoute<ProfileEditRoute>().id

    private val _profile = MutableStateFlow(
        ScheduleProfile.DEFAULT_A.copy(id = "", name = ""),
    )
    val profile: StateFlow<ScheduleProfile> = _profile.asStateFlow()

    private val _saved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val saved = _saved.asSharedFlow()

    val isNew: Boolean get() = requestedId.isEmpty()

    init {
        viewModelScope.launch {
            val s = repo.current().schedule
            _profile.value = s.profile(requestedId)
                ?: ScheduleProfile.DEFAULT_A.copy(id = ScheduleEditor.newCustomProfileId(s), name = "")
        }
    }

    fun setName(name: String) = _profile.update { it.copy(name = name.take(30)) }

    fun set(field: ProfileField, time: LocalTime) = _profile.update {
        when (field) {
            ProfileField.ENTRY_START -> it.copy(entryWindowStart = time)
            ProfileField.ENTRY_END -> it.copy(entryWindowEnd = time)
            ProfileField.EXPECTED_START -> it.copy(expectedStart = time)
            ProfileField.EXPECTED_END -> it.copy(expectedEnd = time)
        }
    }

    fun save() = viewModelScope.launch {
        val p = _profile.value.let { it.copy(name = it.name.trim()) }
        if (ScheduleValidator.validate(p).any { it.blocking }) return@launch
        repo.update { it.copy(schedule = ScheduleEditor.upsertProfile(it.schedule, p)) }
        _saved.tryEmit(Unit)
    }
}

@Composable
fun ProfileEditScreen(onDone: () -> Unit, viewModel: ProfileEditViewModel = hiltViewModel()) {
    val p by viewModel.profile.collectAsStateWithLifecycle()
    val problems = ScheduleValidator.validate(p)
    var editing by remember { mutableStateOf<ProfileField?>(null) }

    LaunchedEffect(Unit) { viewModel.saved.collect { onDone() } }

    StepScaffold(
        title = stringResource(if (viewModel.isNew) R.string.profile_title_new else R.string.profile_title_edit),
        step = null,
        wizard = false,
        primaryEnabled = problems.none { it.blocking },
        onPrimary = viewModel::save,
        primaryLabel = stringResource(R.string.action_save),
        navigationAction = { TextButton(onClick = onDone) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        item {
            OutlinedTextField(
                value = p.name,
                onValueChange = viewModel::setName,
                label = { Text(stringResource(R.string.profile_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            SectionCard {
                Text(stringResource(R.string.profile_window), style = MaterialTheme.typography.titleSmall)
                TimeRow(stringResource(R.string.profile_from), p.entryWindowStart) { editing = ProfileField.ENTRY_START }
                TimeRow(stringResource(R.string.profile_to), p.entryWindowEnd) { editing = ProfileField.ENTRY_END }
                Text(stringResource(R.string.profile_window_hint), style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            SectionCard {
                Text(stringResource(R.string.profile_expected), style = MaterialTheme.typography.titleSmall)
                TimeRow(stringResource(R.string.profile_start), p.expectedStart) { editing = ProfileField.EXPECTED_START }
                TimeRow(stringResource(R.string.profile_end), p.expectedEnd) { editing = ProfileField.EXPECTED_END }
                Text(stringResource(R.string.profile_expected_hint), style = MaterialTheme.typography.bodySmall)
            }
        }
        problems.forEach { problem ->
            item {
                Text(
                    stringResource(problemText(problem)),
                    color = if (problem.blocking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    editing?.let { field ->
        val initial = when (field) {
            ProfileField.ENTRY_START -> p.entryWindowStart
            ProfileField.ENTRY_END -> p.entryWindowEnd
            ProfileField.EXPECTED_START -> p.expectedStart
            ProfileField.EXPECTED_END -> p.expectedEnd
        }
        TimePickerDialog(
            initial = initial,
            title = stringResource(R.string.profile_pick_time),
            onDismiss = { editing = null },
            onConfirm = {
                viewModel.set(field, it)
                editing = null
            },
        )
    }
}

@Composable
private fun TimeRow(label: String, time: LocalTime, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = onClick) { Text(time.format(HHMM), style = MaterialTheme.typography.titleMedium) }
    }
}

private fun problemText(p: ProfileProblem): Int = when (p) {
    ProfileProblem.NAME_BLANK -> R.string.profile_err_name
    ProfileProblem.WINDOW_ORDER -> R.string.profile_err_window
    ProfileProblem.EXPECTED_ORDER -> R.string.profile_err_expected
    ProfileProblem.EXPECTED_START_OUTSIDE_WINDOW -> R.string.profile_warn_outside
}
