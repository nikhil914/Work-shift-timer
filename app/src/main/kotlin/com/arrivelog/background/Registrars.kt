package com.arrivelog.background

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.arrivelog.domain.engine.GeofenceArming
import com.arrivelog.domain.engine.ListenerPlan
import com.arrivelog.domain.engine.Wake
import com.arrivelog.domain.model.DetectionTuning
import com.arrivelog.domain.model.Workplace
import com.arrivelog.platform.DeviceStatus
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Geofences through Play services with a PendingIntent to [GeofenceReceiver]: no foreground
 * service, no location updates. The OS batches location work for all apps' fences.
 */
@Singleton
class GeofenceRegistrar @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceStatus: DeviceStatus,
    private val tuning: DetectionTuning,
) {
    private val client by lazy { LocationServices.getGeofencingClient(context) }

    /** Mutable: Play services adds the event extras. Explicit component, so this is safe. */
    private val pendingIntent: PendingIntent by lazy {
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, GeofenceReceiver::class.java).setAction(GeofenceReceiver.ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    @SuppressLint("MissingPermission")
    suspend fun register(plan: ListenerPlan, workplaces: List<Workplace>, now: Instant): Result<Unit> {
        if (plan.geofence == GeofenceArming.OFF) return removeAll()
        if (!deviceStatus.granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !deviceStatus.granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        ) {
            return Result.failure(IllegalStateException("NO_BACKGROUND_LOCATION_PERMISSION"))
        }
        if (workplaces.isEmpty()) return removeAll()

        // Safety net for battery: fences expire on their own one hour after their planned removal,
        // even if the disarm wake never runs.
        val expiresInMs = plan.nextChangeAt
            ?.let { Duration.between(now, it).plusHours(1).toMillis().coerceAtLeast(MIN_EXPIRY_MS) }
            ?: Geofence.NEVER_EXPIRE
        val transitions = when (plan.geofence) {
            GeofenceArming.ARRIVAL -> Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_DWELL
            GeofenceArming.PRESENCE -> Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT
            GeofenceArming.OFF -> 0
        }
        val fences = workplaces.map { w ->
            Geofence.Builder()
                .setRequestId(requestId(w.id))
                .setCircularRegion(w.latitude, w.longitude, Workplace.clampRadius(w.radiusMeters).toFloat())
                .setExpirationDuration(expiresInMs)
                .setTransitionTypes(transitions)
                .setLoiteringDelay(tuning.loiteringDelay.toMillis().toInt())
                .setNotificationResponsiveness(plan.responsivenessMs)
                .build()
        }
        val request = GeofencingRequest.Builder()
            // ARRIVAL: if already inside when armed, report it (arrival logged at arm time, flagged).
            // PRESENCE, and ARRIVAL after Undo: re-registering while inside must not look like a
            // new arrival (the planner turns initialTrigger off for both).
            .setInitialTrigger(
                if (plan.initialTrigger) GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_DWELL else 0,
            )
            .addGeofences(fences)
            .build()
        return runCatching {
            client.removeGeofences(pendingIntent).await() // drop fences of deleted workplaces
            client.addGeofences(request, pendingIntent).await()
            Unit
        }.recoverCatching { e -> throw IllegalStateException(describe(e), e) }
    }

    suspend fun removeAll(): Result<Unit> = runCatching {
        client.removeGeofences(pendingIntent).await()
        Unit
    }.recoverCatching { e -> throw IllegalStateException(describe(e), e) }

    private fun describe(e: Throwable): String =
        (e as? ApiException)?.let { "GEOFENCE_${GeofenceStatusCodes.getStatusCodeString(it.statusCode)}" } ?: (e.message ?: e.javaClass.simpleName)

    companion object {
        private const val REQUEST_CODE = 1001
        private const val PREFIX = "wp-"
        private const val MIN_EXPIRY_MS = 15 * 60_000L

        fun requestId(workplaceId: Long) = "$PREFIX$workplaceId"
        fun workplaceId(requestId: String): Long? = requestId.removePrefix(PREFIX).toLongOrNull()
    }
}

/**
 * Office Wi-Fi without scanning: the system sends [WifiReceiver] an intent whenever a Wi-Fi
 * network becomes available, even if the app is not running. Note: this variant has no "lost"
 * callback; exits come from the geofence.
 */
@Singleton
class WifiCallbackRegistrar @Inject constructor(@ApplicationContext private val context: Context) {
    private val connectivity get() = context.getSystemService(ConnectivityManager::class.java)

    /** Mutable: the system attaches the Network to the intent. Explicit component. */
    private val pendingIntent: PendingIntent by lazy {
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, WifiReceiver::class.java).setAction(WifiReceiver.ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    fun register(): Result<Unit> = runCatching {
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        // Registering the same PendingIntent again replaces the earlier request.
        connectivity?.registerNetworkCallback(request, pendingIntent) ?: error("NO_CONNECTIVITY_SERVICE")
    }

    fun unregister(): Result<Unit> = runCatching {
        try {
            connectivity?.unregisterNetworkCallback(pendingIntent)
        } catch (_: IllegalArgumentException) {
            // Not registered (e.g. after reboot): nothing to do.
        }
    }

    private companion object {
        const val REQUEST_CODE = 1002
    }
}

/**
 * The one self-wake. setAndAllowWhileIdle is inexact (no SCHEDULE_EXACT_ALARM), but unlike a
 * WorkManager job it is allowed to fire in Doze, so the 06:15 arm is not postponed until the
 * phone is next picked up. Android may defer it by several minutes; the 15-minute lead absorbs that.
 */
@Singleton
class WakeScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    private val alarms get() = context.getSystemService(AlarmManager::class.java)

    private fun pendingIntent(reason: String?): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, WakeReceiver::class.java).setAction(WakeReceiver.ACTION).putExtra(WakeReceiver.EXTRA_REASON, reason),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun schedule(wake: Wake?) {
        val am = alarms ?: return
        if (wake == null) {
            am.cancel(pendingIntent(null))
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wake.at.toEpochMilli(), pendingIntent(wake.reason.name))
        }
    }

    private companion object {
        const val REQUEST_CODE = 1003
    }
}
