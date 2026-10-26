package com.arrivelog.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.arrivelog.MainActivity
import com.arrivelog.R
import com.arrivelog.domain.day.DayChip
import com.arrivelog.domain.day.DaySummary
import com.arrivelog.domain.day.DaySummaryCalculator
import com.arrivelog.domain.model.DetectionTuning
import com.arrivelog.domain.repository.SettingsRepository
import com.arrivelog.domain.repository.WorkDayRepository
import com.arrivelog.ui.export.label
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun settings(): SettingsRepository
    fun workDays(): WorkDayRepository
    fun clock(): Clock
    fun tuning(): DetectionTuning
}

/**
 * Home-screen widget: today's arrival time and status (spec section 8). No periodic updates
 * (updatePeriodMillis = 0, spec 5b): it is redrawn only by [WidgetUpdater] when today's record
 * changes, so it costs nothing while nothing happens.
 */
class TodayWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
        val clock = deps.clock()
        val now = clock.instant()
        val today = LocalDate.now(clock)
        val app = deps.settings().current()
        val summary = DaySummaryCalculator.of(today, app.schedule, deps.workDays().get(today), clock.zone, now, deps.tuning())
        val texts = WidgetTexts(
            date = today.format(DATE),
            time = summary.arrivalAt?.atZone(summary.zone)?.format(HHMM) ?: "—",
            status = context.chipText(summary),
            exit = summary.exitAt?.atZone(summary.zone)?.format(HHMM)?.let { context.getString(R.string.widget_left, it) },
        )
        provideContent { Content(texts) }
    }

    @Composable
    private fun Content(t: WidgetTexts) {
        GlanceTheme {
            Column(
                modifier = GlanceModifier.fillMaxSize()
                    .background(GlanceTheme.colors.widgetBackground)
                    .cornerRadius(16.dp)
                    .padding(12.dp)
                    .clickable(actionStartActivity<MainActivity>()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.Start,
            ) {
                Text(t.date, style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant))
                Text(t.time, style = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface))
                Text(t.status, style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.primary))
                t.exit?.let { Text(it, style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant)) }
            }
        }
    }

    private data class WidgetTexts(val date: String, val time: String, val status: String, val exit: String?)

    private companion object {
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")
        val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}

private fun Context.chipText(d: DaySummary): String = when (val chip = d.chip) {
    DayChip.OnTime -> getString(R.string.chip_on_time)
    is DayChip.Late -> getString(R.string.chip_late, chip.minutes)
    DayChip.Arrived -> getString(R.string.chip_arrived)
    DayChip.NotYet -> getString(R.string.chip_not_yet)
    DayChip.Missing -> getString(R.string.chip_missing)
    is DayChip.Status -> getString(chip.status.label())
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}

/**
 * Redraws the widget only when what it shows would change (date, arrival, exit, status, or the
 * moment "Not yet" turns into "No arrival logged"). Called at the end of every sync, which already
 * runs after each arrival, exit, edit, undo and wake, so no extra wake-ups are added for it.
 */
@Singleton
class WidgetUpdater @Inject constructor(@ApplicationContext private val context: Context) {
    private val last = AtomicReference<String?>(null)

    suspend fun refreshIfChanged(signature: String) {
        if (last.getAndSet(signature) == signature) return
        try {
            TodayWidget().updateAll(context)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A launcher that rejects the update must never break detection; next change retries.
            last.set(null)
        }
    }
}
