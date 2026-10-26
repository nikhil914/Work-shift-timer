package com.arrivelog.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arrivelog.R
import com.arrivelog.domain.stats.ArrivalPoint
import com.arrivelog.domain.stats.PeriodStats
import com.arrivelog.domain.stats.StatsPeriod
import com.arrivelog.ui.common.SectionCard
import com.arrivelog.ui.home.formatDuration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Spec section 4: averages, on-time %, late days, hours, earliest / latest, arrival chart. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(onBack: () -> Unit, viewModel: StatsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatsPeriod.entries.forEach { p ->
                        FilterChip(
                            selected = state.period == p,
                            onClick = { viewModel.setPeriod(p) },
                            label = { Text(stringResource(if (p == StatsPeriod.WEEK) R.string.stats_week else R.string.stats_month)) },
                        )
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = viewModel::previous) { Text(stringResource(R.string.stats_previous)) }
                    Text(
                        periodLabel(state.period, state.from, state.to),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                    )
                    TextButton(onClick = viewModel::next, enabled = state.canGoForward) { Text(stringResource(R.string.stats_next)) }
                }
            }
            val stats = state.stats
            if (stats != null) {
                if (stats.ratedArrivals == 0 && stats.points.isEmpty()) {
                    item { Text(stringResource(R.string.stats_empty), style = MaterialTheme.typography.bodyLarge) }
                } else {
                    item { Summary(stats) }
                    if (state.period == StatsPeriod.MONTH) {
                        // The x axis spans the whole month, so the days still to come show as empty space.
                        item { ArrivalChartCard(stats.points, from = state.from ?: stats.from, to = state.to ?: stats.to) }
                    }
                    if (state.period == StatsPeriod.MONTH && stats.weekly.isNotEmpty()) item { Weekly(stats) }
                }
            }
        }
    }
}

@Composable
private fun Summary(s: PeriodStats) {
    SectionCard {
        Text(
            s.onTimePercent?.let { stringResource(R.string.stats_on_time_percent, it) } ?: stringResource(R.string.stats_on_time_none),
            style = MaterialTheme.typography.displaySmall,
        )
        Text(stringResource(R.string.stats_on_time_detail, s.onTimeDays, s.ratedArrivals), style = MaterialTheme.typography.bodyMedium)
        StatRow(
            stringResource(R.string.stats_late_days),
            if (s.averageLateMinutes != null) {
                stringResource(R.string.stats_late_value, s.lateDays, s.averageLateMinutes)
            } else {
                s.lateDays.toString()
            },
        )
        StatRow(stringResource(R.string.stats_average), s.averageArrival?.format(HHMM) ?: DASH)
        StatRow(stringResource(R.string.stats_earliest), s.earliest?.let { stringResource(R.string.stats_dated, it.time.format(HHMM), it.date.format(DAY)) } ?: DASH)
        StatRow(stringResource(R.string.stats_latest), s.latest?.let { stringResource(R.string.stats_dated, it.time.format(HHMM), it.date.format(DAY)) } ?: DASH)
        StatRow(stringResource(R.string.stats_total_hours), stringResource(R.string.stats_hours_value, formatDuration(s.totalWorked), s.daysWithHours))
        if (s.missingDays > 0) StatRow(stringResource(R.string.stats_missing), s.missingDays.toString())
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Weekly(s: PeriodStats) {
    SectionCard {
        Text(stringResource(R.string.stats_weekly_title), style = MaterialTheme.typography.titleSmall)
        s.weekly.forEach { w ->
            StatRow(
                stringResource(R.string.stats_week_of, w.weekStart.format(DAY)),
                stringResource(R.string.stats_week_value, w.averageArrival.format(HHMM), w.arrivals),
            )
        }
    }
}

@Composable
private fun ArrivalChartCard(points: List<ArrivalPoint>, from: LocalDate, to: LocalDate) {
    SectionCard {
        Text(stringResource(R.string.stats_chart_title), style = MaterialTheme.typography.titleSmall)
        if (points.isEmpty()) {
            Text(stringResource(R.string.stats_empty), style = MaterialTheme.typography.bodySmall)
        } else {
            ArrivalChart(points, from, to)
            Text(stringResource(R.string.stats_chart_legend), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** One dot per arrival (red when late), against a dashed expected-start line. Earlier is higher. */
@Composable
private fun ArrivalChart(points: List<ArrivalPoint>, from: LocalDate, to: LocalDate) {
    val expected = points.mapNotNull { it.expectedMinute }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    val minutes = points.map { it.minuteOfDay } + listOfNotNull(expected)
    // Round the scale out to whole quarter hours so the labels read cleanly.
    val top = ((minutes.min() - CHART_MARGIN) / 15) * 15
    val bottom = ((minutes.max() + CHART_MARGIN + 14) / 15) * 15
    val days = (ChronoUnit.DAYS.between(from, to) + 1).toInt().coerceAtLeast(1)

    val onTimeColor = MaterialTheme.colorScheme.primary
    val lateColor = MaterialTheme.colorScheme.error
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val measurer = rememberTextMeasurer()
    val description = stringResource(
        R.string.stats_chart_description,
        points.size,
        points.count { it.late },
        expected?.let { minuteLabel(it) } ?: DASH,
    )

    Canvas(Modifier.fillMaxWidth().height(200.dp).semantics { contentDescription = description }) {
        val labelWidth = 44.dp.toPx()
        val plotLeft = labelWidth
        val plotWidth = size.width - labelWidth
        fun y(minute: Int): Float = (minute - top).toFloat() / (bottom - top).coerceAtLeast(1) * size.height
        fun x(date: LocalDate): Float =
            plotLeft + (ChronoUnit.DAYS.between(from, date).toFloat() + 0.5f) / days * plotWidth

        listOf(top, bottom).forEach { m ->
            drawLine(gridColor, Offset(plotLeft, y(m)), Offset(size.width, y(m)), strokeWidth = 1.dp.toPx())
            drawLabel(measurer, minuteLabel(m), labelStyle, y(m))
        }
        expected?.let { m ->
            drawLine(
                onTimeColor,
                Offset(plotLeft, y(m)),
                Offset(size.width, y(m)),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())),
            )
            drawLabel(measurer, minuteLabel(m), labelStyle.copy(color = onTimeColor), y(m))
        }
        points.forEach { p ->
            drawCircle(if (p.late) lateColor else onTimeColor, radius = 4.dp.toPx(), center = Offset(x(p.date), y(p.minuteOfDay)))
        }
    }
}

private fun DrawScope.drawLabel(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    centerY: Float,
) {
    val layout = measurer.measure(text, style)
    val top = (centerY - layout.size.height / 2f).coerceIn(0f, (size.height - layout.size.height).coerceAtLeast(0f))
    drawText(layout, topLeft = Offset(0f, top))
}

@Composable
private fun periodLabel(period: StatsPeriod, from: LocalDate?, to: LocalDate?): String {
    if (from == null || to == null) return ""
    return when (period) {
        StatsPeriod.WEEK -> stringResource(R.string.stats_range, from.format(DAY), to.format(DAY))
        StatsPeriod.MONTH -> from.format(MONTH)
    }
}

private fun minuteLabel(minute: Int): String = minute.coerceIn(0, LAST_MINUTE).let { LocalTime.of(it / 60, it % 60) }.format(HHMM)

private const val CHART_MARGIN = 10
private const val LAST_MINUTE = 23 * 60 + 59
private const val DASH = "—"
private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
private val MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")
