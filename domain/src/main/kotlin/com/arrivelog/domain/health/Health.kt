package com.arrivelog.domain.health

import com.arrivelog.domain.engine.GeofenceArming
import com.arrivelog.domain.engine.ListenerPlan
import com.arrivelog.domain.model.DetectionMode
import com.arrivelog.domain.setup.DeviceState

enum class HealthItem {
    BACKGROUND_LOCATION,
    PRECISE_LOCATION,
    LOCATION_ON,
    NOTIFICATIONS,
    NEARBY_WIFI,
    BATTERY_UNRESTRICTED,

    /** What the planner wants registered right now is what is registered. */
    GEOFENCES,
    WIFI_CALLBACK,
}

data class HealthCheck(
    val item: HealthItem,
    val ok: Boolean,
    /** False when the current detection mode does not use it (shown, but never red). */
    val relevant: Boolean = true,
)

/** The green/red checklist of the Health screen (spec section 4). Pure, so it is testable. */
object HealthEvaluator {
    fun evaluate(
        device: DeviceState,
        mode: DetectionMode,
        expected: ListenerPlan,
        armedGeofence: GeofenceArming,
        wifiArmed: Boolean,
    ): List<HealthCheck> = listOf(
        HealthCheck(HealthItem.BACKGROUND_LOCATION, device.backgroundLocation),
        HealthCheck(HealthItem.PRECISE_LOCATION, device.fineLocation),
        HealthCheck(HealthItem.LOCATION_ON, device.locationEnabled),
        HealthCheck(HealthItem.NOTIFICATIONS, device.notifications),
        HealthCheck(HealthItem.NEARBY_WIFI, device.nearbyWifi || !mode.usesWifi, relevant = mode.usesWifi),
        HealthCheck(HealthItem.BATTERY_UNRESTRICTED, device.batteryUnrestricted),
        // Off when nothing is wanted (weekend, after disarm) is healthy, not an error.
        HealthCheck(HealthItem.GEOFENCES, armedGeofence == expected.geofence),
        HealthCheck(HealthItem.WIFI_CALLBACK, wifiArmed == expected.wifiCallback, relevant = mode.usesWifi),
    )

    fun allGood(checks: List<HealthCheck>): Boolean = checks.all { it.ok }
}
