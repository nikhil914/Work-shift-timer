package com.arrivelog.platform

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.arrivelog.domain.setup.DeviceState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Reads permission / battery / location state. Cheap, synchronous, safe on the main thread. */
@Singleton
class DeviceStatus @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun snapshot(): DeviceState = DeviceState(
        fineLocation = granted(Manifest.permission.ACCESS_FINE_LOCATION),
        backgroundLocation = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        nearbyWifi = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(Manifest.permission.NEARBY_WIFI_DEVICES),
        locationEnabled = context.getSystemService(android.location.LocationManager::class.java)
            ?.let(LocationManagerCompat::isLocationEnabled) ?: false,
        batteryUnrestricted = context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) ?: false,
        wifiEnabled = context.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled ?: false,
    )

    fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** True when the Maps API key from local.properties / CI secret made it into the manifest. */
    val hasMapsKey: Boolean by lazy {
        runCatching {
            val info = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            !info.metaData?.getString("com.google.android.geo.API_KEY").isNullOrBlank()
        }.getOrDefault(false)
    }

    /** Samsung One UI: the manufacturer is enough to decide whether to show Device-care steps. */
    val isSamsung: Boolean get() = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
}

/** Settings deep links. Every one has a fallback because OEMs remove or rename screens. */
object SettingsIntents {
    fun appDetails(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    /** Play-compliant replacement for REQUEST_IGNORE_BATTERY_OPTIMIZATIONS: the system list. */
    fun batteryOptimizationList() = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun notifications(context: Context) = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    fun location() = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)

    fun wifi() = Intent(Settings.ACTION_WIFI_SETTINGS)

    fun launch(context: Context, intent: Intent, fallback: Intent = appDetails(context)) {
        val flagged = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(flagged)
        } catch (_: ActivityNotFoundException) {
            context.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: SecurityException) {
            context.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
