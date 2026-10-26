package com.arrivelog.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.arrivelog.background.DetectionCoordinator
import com.arrivelog.background.runAsync
import com.arrivelog.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import java.time.LocalDate
import javax.inject.Inject

/** Undo / Log now, handled without opening the app. */
// Explicit Hilt base so super.onReceive() (which performs the injection) is callable.
@AndroidEntryPoint(BroadcastReceiver::class)
class NotificationActionReceiver : Hilt_NotificationActionReceiver() {
    @Inject lateinit var coordinator: DetectionCoordinator

    @Inject @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val date = intent.getStringExtra(EXTRA_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return
        when (intent.action) {
            ACTION_UNDO -> runAsync(scope, "undo") { coordinator.undoArrival(date) }
            ACTION_LOG_NOW -> runAsync(scope, "log-now") { coordinator.logNow() }
        }
    }

    companion object {
        const val ACTION_UNDO = "com.arrivelog.action.UNDO_ARRIVAL"
        const val ACTION_LOG_NOW = "com.arrivelog.action.LOG_NOW"
        const val EXTRA_DATE = "date"
    }
}
