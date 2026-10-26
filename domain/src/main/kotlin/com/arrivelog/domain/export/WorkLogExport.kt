package com.arrivelog.domain.export

import com.arrivelog.domain.day.DayChip
import com.arrivelog.domain.day.DaySummaryCalculator
import com.arrivelog.domain.model.DetectionTuning
import com.arrivelog.domain.model.ScheduleSettings
import com.arrivelog.domain.model.SignalSource
import com.arrivelog.domain.model.WorkDay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A spreadsheet cell. Numbers stay numbers in XLSX so sums work in Excel / Sheets. */
sealed interface Cell {
    data class Text(val value: String) : Cell
    data class Number(val value: Double) : Cell
    data object Empty : Cell
}

data class ExportTable(val headers: List<String>, val rows: List<List<Cell>>)

enum class ExportColumn { DATE, WEEKDAY, STATUS, ARRIVAL, EXIT, HOURS, LATE_MINUTES, ARRIVAL_SOURCE, EXIT_SOURCE, EDITED, NOTE }

/** Localised words the table needs. The app fills these from string resources. */
data class ExportLabels(
    val headers: Map<ExportColumn, String>,
    val chip: (DayChip) -> String,
    val source: (SignalSource) -> String,
    val yes: String,
)

/**
 * Spec section 8: one row per calendar day in the range (so gaps are visible), using the same
 * rules as the home chips. Times are local to the day's own zone, hours are decimal.
 */
object WorkLogExport {
    fun table(
        from: LocalDate,
        to: LocalDate,
        records: List<WorkDay>,
        schedule: ScheduleSettings,
        zone: ZoneId,
        now: Instant,
        labels: ExportLabels,
        tuning: DetectionTuning = DetectionTuning(),
    ): ExportTable {
        val byDate = records.associateBy { it.date }
        val today = now.atZone(zone).toLocalDate()
        val last = minOf(to, today)
        val rows = if (last.isBefore(from)) {
            emptyList()
        } else {
            generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.map { date ->
                val record = byDate[date]
                val d = DaySummaryCalculator.of(date, schedule, record, zone, now, tuning)
                // Hours only for closed days: an open day's live counter is not a result yet.
                val hours = d.worked?.takeIf { d.exitAt != null }?.let { it.toMinutes() / 60.0 }
                ExportColumn.entries.map { column ->
                    when (column) {
                        ExportColumn.DATE -> Cell.Text(date.toString())
                        ExportColumn.WEEKDAY -> Cell.Text(date.format(WEEKDAY))
                        ExportColumn.STATUS -> Cell.Text(labels.chip(d.chip))
                        ExportColumn.ARRIVAL -> d.arrivalAt.time(d.zone)
                        ExportColumn.EXIT -> d.exitAt.time(d.zone)
                        ExportColumn.HOURS -> hours?.let { Cell.Number(round2(it)) } ?: Cell.Empty
                        ExportColumn.LATE_MINUTES -> (d.chip as? DayChip.Late)?.let { Cell.Number(it.minutes.toDouble()) } ?: Cell.Empty
                        ExportColumn.ARRIVAL_SOURCE -> d.arrivalSource?.let { Cell.Text(labels.source(it)) } ?: Cell.Empty
                        ExportColumn.EXIT_SOURCE -> record?.exitSource?.takeIf { d.exitAt != null }?.let { Cell.Text(labels.source(it)) } ?: Cell.Empty
                        ExportColumn.EDITED -> if (d.edited) Cell.Text(labels.yes) else Cell.Empty
                        ExportColumn.NOTE -> d.note?.takeIf { it.isNotBlank() }?.let { Cell.Text(it) } ?: Cell.Empty
                    }
                }
            }.toList()
        }
        return ExportTable(ExportColumn.entries.map { labels.headers[it] ?: it.name }, rows)
    }

    private fun Instant?.time(zone: ZoneId): Cell = this?.let { Cell.Text(it.atZone(zone).format(HHMM)) } ?: Cell.Empty

    private fun round2(v: Double): Double = Math.round(v * 100) / 100.0

    private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val WEEKDAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE")
}
