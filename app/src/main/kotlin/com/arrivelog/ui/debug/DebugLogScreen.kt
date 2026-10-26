package com.arrivelog.ui.debug

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.arrivelog.R
import com.arrivelog.background.ArmingState
import com.arrivelog.background.ArmingStateStore
import com.arrivelog.background.DetectionCoordinator
import com.arrivelog.domain.model.RawEvent
import com.arrivelog.domain.model.RawEventType
import com.arrivelog.domain.repository.EventLogRepository
import com.arrivelog.ui.common.SectionCard
import com.arrivelog.ui.common.StepScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class DebugLogViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventLog: EventLogRepository,
    arming: ArmingStateStore,
    private val coordinator: DetectionCoordinator,
    private val clock: Clock,
) : ViewModel() {
    val events: StateFlow<List<RawEvent>> = eventLog.observeRecent(LIMIT).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val arming: StateFlow<ArmingState> = arming.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ArmingState())

    private val _share = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val share = _share.asSharedFlow()

    val zone: ZoneId get() = clock.zone

    /** Wake-ups the app caused or received today (alarms, events, boot, app opens). */
    fun wakesToday(events: List<RawEvent>): Int {
        val today = LocalDate.now(clock)
        return events.count { it.type == RawEventType.SYSTEM && it.reason.startsWith("WAKE:") && it.receivedAt.atZone(clock.zone).toLocalDate() == today }
    }

    fun syncNow() = viewModelScope.launch { coordinator.sync("MANUAL", force = true) }

    fun export() = viewModelScope.launch {
        val now = clock.instant()
        val rows = eventLog.between(now.minus(Duration.ofDays(EXPORT_DAYS)), now)
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            File(dir, "arrivelog-events-${LocalDate.now(clock)}.csv").apply {
                bufferedWriter().use { w ->
                    w.appendLine("id,event_time,received_time,delay_s,type,workplace_id,accuracy_m,ssid,bssid,accepted,reason")
                    rows.forEach { e ->
                        w.appendLine(
                            listOf(
                                e.id.toString(),
                                e.at.atZone(clock.zone).toOffsetDateTime().toString(),
                                e.receivedAt.atZone(clock.zone).toOffsetDateTime().toString(),
                                Duration.between(e.at, e.receivedAt).seconds.toString(),
                                e.type.name,
                                e.workplaceId?.toString().orEmpty(),
                                e.accuracyM?.toString().orEmpty(),
                                e.ssid.orEmpty(),
                                e.bssid.orEmpty(),
                                e.accepted.toString(),
                                e.reason,
                            ).joinToString(",") { csv(it) },
                        )
                    }
                }
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        _share.tryEmit(Intent.createChooser(send, null))
    }

    private fun csv(v: String): String = if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    private companion object {
        const val LIMIT = 500
        const val EXPORT_DAYS = 90L
    }
}

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE HH:mm:ss")

@Composable
fun DebugLogScreen(onBack: () -> Unit, viewModel: DebugLogViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val events by viewModel.events.collectAsStateWithLifecycle()
    val arming by viewModel.arming.collectAsStateWithLifecycle()
    val zone = viewModel.zone

    LaunchedEffect(Unit) { viewModel.share.collect { context.startActivity(it) } }

    StepScaffold(
        title = stringResource(R.string.debug_title),
        step = null,
        wizard = false,
        primaryEnabled = true,
        onPrimary = viewModel::export,
        primaryLabel = stringResource(R.string.debug_export),
        navigationAction = { TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) } },
        actions = { TextButton(onClick = viewModel::syncNow) { Text(stringResource(R.string.debug_sync)) } },
    ) {
        item {
            SectionCard {
                Text(stringResource(R.string.debug_status), style = MaterialTheme.typography.titleSmall)
                Mono(stringResource(R.string.debug_geofence, arming.geofenceArming.name, arming.lastRegisteredAt.fmt(zone)))
                Mono(stringResource(R.string.debug_wifi, stringResource(if (arming.wifiArmed) R.string.shell_on else R.string.shell_off)))
                Mono(stringResource(R.string.debug_next_wake, arming.nextWakeAt.fmt(zone), arming.nextWakeReason.orEmpty()))
                Mono(stringResource(R.string.debug_wakes_today, viewModel.wakesToday(events)))
                arming.lastError?.let { Mono(stringResource(R.string.debug_last_error, it, arming.lastErrorAt.fmt(zone))) }
            }
        }
        if (events.isEmpty()) item { Text(stringResource(R.string.shell_events_empty)) }
        items(events, key = { it.id }) { e ->
            val delay = Duration.between(e.at, e.receivedAt).seconds
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Mono("${e.at.atZone(zone).format(TIME)}  ${e.type.name}  ${if (e.accepted) "✓" else "✗"}")
                Mono(e.reason)
                val extra = listOfNotNull(
                    e.workplaceId?.let { "wp=$it" },
                    e.accuracyM?.let { "±${it.toInt()}m" },
                    e.ssid?.let { "ssid=$it" },
                    if (delay > 0) "+${delay}s" else null,
                ).joinToString("  ")
                if (extra.isNotEmpty()) Mono(extra)
                HorizontalDivider(Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun Mono(text: String) = Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)

private fun Instant?.fmt(zone: ZoneId): String = this?.atZone(zone)?.format(TIME) ?: "—"
