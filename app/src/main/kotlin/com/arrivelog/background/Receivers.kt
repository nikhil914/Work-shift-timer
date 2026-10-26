package com.arrivelog.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.arrivelog.di.ApplicationScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

/**
 * Hands receiver work to a coroutine with goAsync(): the process stays alive until finish(),
 * without a foreground service. The work is a few DB transactions (milliseconds); the timeout
 * keeps us well inside the system's broadcast limit.
 */
internal fun BroadcastReceiver.runAsync(scope: CoroutineScope, tag: String, block: suspend () -> Unit) {
    val pending = goAsync()
    scope.launch {
        try {
            withTimeout(RECEIVER_TIMEOUT_MS) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("ArriveLog", "$tag failed", e)
        } finally {
            pending.finish()
        }
    }
}

private const val RECEIVER_TIMEOUT_MS = 9_000L

// Explicit Hilt base so super.onReceive() (which performs the injection) is callable.
@AndroidEntryPoint(BroadcastReceiver::class)
class GeofenceReceiver : Hilt_GeofenceReceiver() {
    @Inject lateinit var coordinator: DetectionCoordinator

    @Inject @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        runAsync(scope, "geofence") { coordinator.onGeofenceEvent(intent) }
    }

    companion object {
        const val ACTION = "com.arrivelog.action.GEOFENCE"
    }
}

// Explicit Hilt base so super.onReceive() (which performs the injection) is callable.
@AndroidEntryPoint(BroadcastReceiver::class)
class WifiReceiver : Hilt_WifiReceiver() {
    @Inject lateinit var coordinator: DetectionCoordinator

    @Inject @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        runAsync(scope, "wifi") { coordinator.onWifiAvailable(intent) }
    }

    companion object {
        const val ACTION = "com.arrivelog.action.WIFI_AVAILABLE"
    }
}

// Explicit Hilt base so super.onReceive() (which performs the injection) is callable.
@AndroidEntryPoint(BroadcastReceiver::class)
class WakeReceiver : Hilt_WakeReceiver() {
    @Inject lateinit var coordinator: DetectionCoordinator

    @Inject @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val reason = intent.getStringExtra(EXTRA_REASON) ?: "ALARM"
        runAsync(scope, "wake") { coordinator.sync(reason) }
    }

    companion object {
        const val ACTION = "com.arrivelog.action.WAKE"
        const val EXTRA_REASON = "reason"
    }
}

/**
 * Geofences and the Wi-Fi PendingIntent do not survive a reboot; alarms do not survive a reboot
 * or a time/zone change. All of these force a full re-registration.
 */
// Explicit Hilt base so super.onReceive() (which performs the injection) is callable.
@AndroidEntryPoint(BroadcastReceiver::class)
class SystemEventsReceiver : Hilt_SystemEventsReceiver() {
    @Inject lateinit var coordinator: DetectionCoordinator

    @Inject @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "BOOT"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "APP_UPDATED"
            Intent.ACTION_TIMEZONE_CHANGED -> "TIMEZONE_CHANGED"
            Intent.ACTION_TIME_CHANGED -> "TIME_CHANGED"
            else -> return
        }
        runAsync(scope, reason) { coordinator.sync(reason, force = true) }
    }
}

/** Once-a-day safety net (spec 5: daily re-registration), in case an alarm was lost. */
@HiltWorker
class WatchdogWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: DetectionCoordinator,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        coordinator.sync("WATCHDOG", force = true)
        return Result.success()
    }

    companion object {
        const val NAME = "arrivelog-watchdog"
    }
}
