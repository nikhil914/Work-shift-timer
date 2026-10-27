package com.arrivelog.ui.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arrivelog.R
import com.arrivelog.domain.day.DayChip
import com.arrivelog.domain.export.CsvWriter
import com.arrivelog.domain.export.ExportColumn
import com.arrivelog.domain.export.ExportLabels
import com.arrivelog.domain.export.WorkLogExport
import com.arrivelog.domain.export.XlsxWriter
import com.arrivelog.domain.model.DayStatus
import com.arrivelog.domain.model.DetectionTuning
import com.arrivelog.domain.model.SignalSource
import com.arrivelog.domain.repository.SettingsRepository
import com.arrivelog.domain.repository.WorkDayRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

enum class ExportFormat(val extension: String, val mime: String) {
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    CSV("csv", "text/csv"),
}

enum class RangePreset { THIS_MONTH, LAST_MONTH, LAST_3_MONTHS, THIS_YEAR, CUSTOM }

data class ExportUiState(
    val preset: RangePreset = RangePreset.THIS_MONTH,
    val from: LocalDate,
    val to: LocalDate,
    val format: ExportFormat = ExportFormat.XLSX,
    val working: Boolean = false,
    val failed: Boolean = false,
)

/** Spec section 8: CSV / XLSX for a date range, handed to the share sheet (Notes, mail, Drive). */
@HiltViewModel
class ExportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val workDays: WorkDayRepository,
    private val tuning: DetectionTuning,
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow(rangeFor(RangePreset.THIS_MONTH).let { (f, t) -> ExportUiState(from = f, to = t) })
    val state: StateFlow<ExportUiState> = _state.asStateFlow()

    private val _share = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val share = _share.asSharedFlow()

    fun setPreset(preset: RangePreset) {
        if (preset == RangePreset.CUSTOM) {
            _state.update { it.copy(preset = preset) }
        } else {
            val (from, to) = rangeFor(preset)
            _state.update { it.copy(preset = preset, from = from, to = to) }
        }
    }

    fun setCustomRange(from: LocalDate, to: LocalDate) =
        _state.update { it.copy(preset = RangePreset.CUSTOM, from = minOf(from, to), to = maxOf(from, to)) }

    fun setFormat(format: ExportFormat) = _state.update { it.copy(format = format) }

    fun export() = viewModelScope.launch {
        val s = _state.value
        _state.update { it.copy(working = true, failed = false) }
        val result = runCatching {
            val app = settings.current()
            val records = workDays.observeRange(s.from, s.to).first()
            val table = WorkLogExport.table(s.from, s.to, records, app.schedule, clock.zone, clock.instant(), labels(), tuning)
            withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                File(dir, "arrivelog-${s.from}-to-${s.to}.${s.format.extension}").apply {
                    outputStream().use { out ->
                        when (s.format) {
                            ExportFormat.CSV -> CsvWriter.write(table, out)
                            ExportFormat.XLSX -> XlsxWriter.write(table, out)
                        }
                    }
                }
            }
        }
        result.onSuccess { file ->
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType(s.format.mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, file.name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            _share.tryEmit(Intent.createChooser(send, null))
        }
        _state.update { it.copy(working = false, failed = result.isFailure) }
    }

    private fun rangeFor(preset: RangePreset): Pair<LocalDate, LocalDate> {
        val today = LocalDate.now(clock)
        return when (preset) {
            RangePreset.THIS_MONTH, RangePreset.CUSTOM -> today.withDayOfMonth(1) to today
            RangePreset.LAST_MONTH -> today.minusMonths(1).let { it.withDayOfMonth(1) to it.with(TemporalAdjusters.lastDayOfMonth()) }
            RangePreset.LAST_3_MONTHS -> today.minusMonths(2).withDayOfMonth(1) to today
            RangePreset.THIS_YEAR -> today.withDayOfYear(1) to today
        }
    }

    private fun labels() = ExportLabels(
        headers = ExportColumn.entries.associateWith { context.getString(it.header()) },
        chip = { chip ->
            when (chip) {
                DayChip.OnTime -> context.getString(R.string.chip_on_time)
                is DayChip.Late -> context.getString(R.string.chip_late, chip.minutes)
                DayChip.Arrived -> context.getString(R.string.chip_arrived)
                DayChip.NotYet -> context.getString(R.string.chip_not_yet)
                DayChip.Missing -> context.getString(R.string.chip_missing)
                is DayChip.Status -> context.getString(chip.status.label())
            }
        },
        source = { context.getString(it.label()) },
        yes = context.getString(R.string.export_yes),
    )
}

private fun ExportColumn.header(): Int = when (this) {
    ExportColumn.DATE -> R.string.export_col_date
    ExportColumn.WEEKDAY -> R.string.export_col_weekday
    ExportColumn.STATUS -> R.string.export_col_status
    ExportColumn.ARRIVAL -> R.string.export_col_arrival
    ExportColumn.EXIT -> R.string.export_col_exit
    ExportColumn.HOURS -> R.string.export_col_hours
    ExportColumn.LATE_MINUTES -> R.string.export_col_late
    ExportColumn.ARRIVAL_SOURCE -> R.string.export_col_arrival_source
    ExportColumn.EXIT_SOURCE -> R.string.export_col_exit_source
    ExportColumn.EDITED -> R.string.export_col_edited
    ExportColumn.NOTE -> R.string.export_col_note
}

fun DayStatus.label(): Int = when (this) {
    DayStatus.WORKING -> R.string.status_working
    DayStatus.DAY_OFF -> R.string.status_day_off
    DayStatus.WFH -> R.string.status_wfh
    DayStatus.LEAVE -> R.string.status_leave
    DayStatus.HOLIDAY -> R.string.status_holiday
    DayStatus.WEEKEND -> R.string.status_weekend
}

private fun SignalSource.label(): Int = when (this) {
    SignalSource.GEOFENCE -> R.string.export_source_geofence
    SignalSource.WIFI -> R.string.export_source_wifi
    SignalSource.MANUAL -> R.string.export_source_manual
}
