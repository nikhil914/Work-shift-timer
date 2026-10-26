package com.arrivelog.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.arrivelog.MainActivity
import com.arrivelog.R
import com.arrivelog.domain.model.SignalSource
import com.arrivelog.domain.schedule.DayTimeline
import com.arrivelog.ui.home.EditFocus
import com.arrivelog.ui.home.EditRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The two notifications of the spec (section 2):
 *  - "Arrived 08:07 — logged" with Edit time / Undo, on its own default-importance channel;
 *  - "No arrival detected today" with Log now / Pick time / Day off·WFH·Leave, high importance.
 * Both are local only. Without the notification permission they are skipped silently; Home
 * still shows the day as needing attention, so nothing is lost.
 */
@Singleton
class Notifier @Inject constructor(@ApplicationContext private val context: Context) {

    fun createChannels() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_ARRIVAL, context.getString(R.string.channel_arrival), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = context.getString(R.string.channel_arrival_desc) },
                NotificationChannel(CHANNEL_MISSED, context.getString(R.string.channel_missed), NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = context.getString(R.string.channel_missed_desc) },
            ),
        )
    }

    fun arrival(date: LocalDate, at: Instant, zone: ZoneId, source: SignalSource, estimated: Boolean, movedEarlier: Boolean) {
        val time = at.atZone(zone).format(HHMM)
        val title = context.getString(if (movedEarlier) R.string.notif_arrival_moved_title else R.string.notif_arrival_title, time)
        val sourceText = context.getString(
            when (source) {
                SignalSource.GEOFENCE -> R.string.notif_source_geofence
                SignalSource.WIFI -> R.string.notif_source_wifi
                SignalSource.MANUAL -> R.string.notif_source_manual
            },
        )
        val text = if (estimated) context.getString(R.string.notif_arrival_estimated, sourceText) else sourceText
        val notification = NotificationCompat.Builder(context, CHANNEL_ARRIVAL)
            .setSmallIcon(R.drawable.ic_stat_arrivelog)
            .setContentTitle(title)
            .setContentText(text)
            .setWhen(at.toEpochMilli())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openApp(EditRequest(date, EditFocus.NONE), REQ_ARRIVAL_OPEN))
            .addAction(0, context.getString(R.string.notif_action_edit_time), openApp(EditRequest(date, EditFocus.ARRIVAL), REQ_ARRIVAL_EDIT))
            .addAction(0, context.getString(R.string.notif_action_undo), broadcast(NotificationActionReceiver.ACTION_UNDO, date, REQ_ARRIVAL_UNDO))
            .build()
        cancel(ID_MISSED)
        post(ID_ARRIVAL, notification)
    }

    /**
     * [suggestion]: presence seen today that could not be logged automatically (e.g. before the
     * window opened); Pick time opens with it preselected.
     */
    fun missedEntry(date: LocalDate, today: LocalDate, timeline: DayTimeline, suggestion: Instant?, zone: ZoneId, repeat: Boolean) {
        val window = context.getString(
            R.string.notif_missed_window,
            timeline.windowStart.atZone(zone).format(HHMM),
            timeline.windowEnd.atZone(zone).format(HHMM),
        )
        val suggestedTime = suggestion?.atZone(zone)?.toLocalTime()?.withSecond(0)?.withNano(0)
        val text = suggestedTime?.let { context.getString(R.string.notif_missed_suggestion, window, it.format(HHMM)) } ?: window
        val builder = NotificationCompat.Builder(context, CHANNEL_MISSED)
            .setSmallIcon(R.drawable.ic_stat_arrivelog)
            .setContentTitle(context.getString(if (repeat) R.string.notif_missed_title_repeat else R.string.notif_missed_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp(EditRequest(date, EditFocus.NONE), REQ_MISSED_OPEN))
        // "Log now" means now, so it is only offered on the day itself.
        if (date == today) {
            builder.addAction(0, context.getString(R.string.notif_action_log_now), broadcast(NotificationActionReceiver.ACTION_LOG_NOW, date, REQ_MISSED_LOG))
        }
        builder
            .addAction(0, context.getString(R.string.notif_action_pick_time), openApp(EditRequest(date, EditFocus.ARRIVAL, suggestedTime), REQ_MISSED_PICK))
            .addAction(0, context.getString(R.string.notif_action_day_off), openApp(EditRequest(date, EditFocus.STATUS), REQ_MISSED_STATUS))
        post(ID_MISSED, builder.build())
    }

    fun cancelArrival() = cancel(ID_ARRIVAL)

    fun cancelMissed() = cancel(ID_MISSED)

    private fun openApp(request: EditRequest, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        request.writeTo(intent)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun broadcast(action: String, date: LocalDate, requestCode: Int): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(action)
            .putExtra(NotificationActionReceiver.EXTRA_DATE, date.toString())
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    // The permission is checked on the line before; lint cannot see through the early return.
    @SuppressLint("MissingPermission")
    private fun post(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }

    private fun cancel(id: Int) = NotificationManagerCompat.from(context).cancel(id)

    private companion object {
        const val CHANNEL_ARRIVAL = "arrival"
        const val CHANNEL_MISSED = "missed_entry"
        const val ID_ARRIVAL = 1
        const val ID_MISSED = 2

        const val REQ_ARRIVAL_OPEN = 10
        const val REQ_ARRIVAL_EDIT = 11
        const val REQ_ARRIVAL_UNDO = 12
        const val REQ_MISSED_OPEN = 20
        const val REQ_MISSED_LOG = 21
        const val REQ_MISSED_PICK = 22
        const val REQ_MISSED_STATUS = 23

        val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
