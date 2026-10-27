package com.arrivelog.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.arrivelog.MainActivity
import com.arrivelog.R
import com.arrivelog.domain.model.SignalSource
import com.arrivelog.platform.DeviceStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** Where a notification tap should land in the app. */
enum class EditFocus { TIME, STATUS }

@Singleton
class Notifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceStatus: DeviceStatus,
) {
    private val manager get() = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ARRIVALS, context.getString(R.string.channel_arrivals), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.channel_arrivals_desc) },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, context.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = context.getString(R.string.channel_reminders_desc) },
        )
    }

    /** "Arrived 08:07 — logged" with Edit time / Undo. Updated in place if the time moves earlier. */
    fun showArrival(date: LocalDate, at: Instant, zone: ZoneId, source: SignalSource, estimated: Boolean) {
        val time = at.atZone(zone).format(HHMM)
        val body = context.getString(
            when {
                estimated -> R.string.notif_arrival_body_estimated
                source == SignalSource.WIFI -> R.string.notif_arrival_body_wifi
                source == SignalSource.GEOFENCE -> R.string.notif_arrival_body_geofence
                else -> R.string.notif_arrival_body_manual
            },
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ARRIVALS)
            .setSmallIcon(R.drawable.ic_stat_arrival)
            .setContentTitle(context.getString(R.string.notif_arrival_title, time))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setTimeoutAfter(ARRIVAL_TIMEOUT_MS)
            .setContentIntent(openApp(date, EditFocus.TIME, REQ_ARRIVAL_OPEN))
            .addAction(0, context.getString(R.string.notif_action_edit_time), openApp(date, EditFocus.TIME, REQ_ARRIVAL_EDIT))
            .addAction(0, context.getString(R.string.notif_action_undo), broadcast(NotificationActionReceiver.ACTION_UNDO, date, null, REQ_ARRIVAL_UNDO))
            .build()
        post(ID_ARRIVAL, notification)
    }

    /**
     * "No arrival detected today": Log now (or "Log 06:05" when presence was seen earlier),
     * Pick time, Not working (Day off / WFH / Leave chooser in the app).
     */
    fun showMissedEntry(date: LocalDate, suggestion: Instant?, zone: ZoneId, reminder: Boolean) {
        val suggested = suggestion?.atZone(zone)?.format(HHMM)
        val body = if (suggested != null) {
            context.getString(R.string.notif_missed_body_suggest, suggested)
        } else {
            context.getString(R.string.notif_missed_body)
        }
        val logLabel = suggested?.let { context.getString(R.string.notif_action_log_at, it) } ?: context.getString(R.string.notif_action_log_now)
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_arrival)
            .setContentTitle(context.getString(if (reminder) R.string.notif_missed_title_again else R.string.notif_missed_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp(date, EditFocus.TIME, REQ_MISSED_OPEN))
            .addAction(0, logLabel, broadcast(NotificationActionReceiver.ACTION_LOG, date, suggestion, REQ_MISSED_LOG))
            .addAction(0, context.getString(R.string.notif_action_pick_time), openApp(date, EditFocus.TIME, REQ_MISSED_PICK))
            .addAction(0, context.getString(R.string.notif_action_not_working), openApp(date, EditFocus.STATUS, REQ_MISSED_STATUS))
            .build()
        post(ID_MISSED, notification)
    }

    /** "Still at work? Add exit time" (optional, off by default): Left now / Pick time. */
    fun showExitReminder(date: LocalDate) {
        val body = context.getString(R.string.notif_exit_body)
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_arrival)
            .setContentTitle(context.getString(R.string.notif_exit_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp(date, EditFocus.TIME, REQ_EXIT_OPEN))
            .addAction(0, context.getString(R.string.notif_action_left_now), broadcast(NotificationActionReceiver.ACTION_LEFT, date, null, REQ_EXIT_LEFT))
            .addAction(0, context.getString(R.string.notif_action_pick_time), openApp(date, EditFocus.TIME, REQ_EXIT_PICK))
            .build()
        post(ID_EXIT, notification)
    }

    fun cancelExitReminder() = manager.cancel(ID_EXIT)

    fun cancelMissed() = manager.cancel(ID_MISSED)

    fun cancelArrival() = manager.cancel(ID_ARRIVAL)

    /** True if a notification can actually be shown (permission on 13+, and not blocked by the user). */
    fun canPost(): Boolean =
        manager.areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || deviceStatus.granted(Manifest.permission.POST_NOTIFICATIONS))

    @SuppressLint("MissingPermission") // checked in canPost()
    private fun post(id: Int, notification: android.app.Notification) {
        if (canPost()) manager.notify(id, notification)
    }

    private fun openApp(date: LocalDate, focus: EditFocus, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_EDIT_DATE, date.toString())
            .putExtra(EXTRA_EDIT_FOCUS, focus.name)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun broadcast(action: String, date: LocalDate, at: Instant?, requestCode: Int): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_DATE, date.toString())
            .apply { if (at != null) putExtra(EXTRA_AT, at.toEpochMilli()) }
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val CHANNEL_ARRIVALS = "arrivals"
        const val CHANNEL_REMINDERS = "reminders"
        const val EXTRA_EDIT_DATE = "com.arrivelog.extra.EDIT_DATE"
        const val EXTRA_EDIT_FOCUS = "com.arrivelog.extra.EDIT_FOCUS"
        const val EXTRA_DATE = "com.arrivelog.extra.DATE"
        const val EXTRA_AT = "com.arrivelog.extra.AT"

        private const val ID_ARRIVAL = 100
        private const val ID_MISSED = 101
        private const val ID_EXIT = 102
        private const val REQ_ARRIVAL_OPEN = 200
        private const val REQ_ARRIVAL_EDIT = 201
        private const val REQ_ARRIVAL_UNDO = 202
        private const val REQ_MISSED_OPEN = 210
        private const val REQ_MISSED_LOG = 211
        private const val REQ_MISSED_PICK = 212
        private const val REQ_MISSED_STATUS = 213
        private const val REQ_EXIT_OPEN = 220
        private const val REQ_EXIT_LEFT = 221
        private const val REQ_EXIT_PICK = 222
        private const val ARRIVAL_TIMEOUT_MS = 12 * 60 * 60 * 1000L

        private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
