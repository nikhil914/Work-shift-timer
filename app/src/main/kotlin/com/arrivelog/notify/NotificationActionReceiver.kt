package com.arrivelog.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.arrivelog.background.DetectionCoordinator
import com.arrivelog.background.runAsync
import com.arrivelog.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

/** "Undo" on the arrival notification and "Log now" on the missed-entry one: no UI needed. */
@AndroidEntryPoint(BroadcastReceiver::class)
class NotificationActionReceiver : Hilt_NotificationActionReceiver() {
    @Inject lateinit var coordinator: DetectionCoordinator

    @Inject @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val date = intent.getStringExtra(Notifier.EXTRA_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return
        when (intent.action) {
            ACTION_UNDO -> runAsync(scope, "undo") { coordinator.undoArrival(date) }
            ACTION_LOG -> {
                val at = intent.getLongExtra(Notifier.EXTRA_AT, -1L).takeIf { it > 0 }?.let(Instant::ofEpochMilli)
                runAsync(scope, "log") { coordinator.logArrival(at) }
            }
        }
    }

    companion object {
        const val ACTION_UNDO = "com.arrivelog.action.UNDO_ARRIVAL"
        const val ACTION_LOG = "com.arrivelog.action.LOG_ARRIVAL"
    }
}
