package com.arrivelog.domain.setup

import com.arrivelog.domain.model.AppSettings
import com.arrivelog.domain.model.Workplace
import com.arrivelog.domain.schedule.ResolvedDay
import com.arrivelog.domain.schedule.ScheduleResolver
import java.time.DayOfWeek
import java.time.LocalDate

/** Device state as plain booleans, so readiness rules are testable without Android. */
data class DeviceState(
    val fineLocation: Boolean,
    val backgroundLocation: Boolean,
    val notifications: Boolean,
    /** API 33+ only; true on older versions where the permission does not exist. */
    val nearbyWifi: Boolean,
    val locationEnabled: Boolean,
    val batteryUnrestricted: Boolean,
    val wifiEnabled: Boolean,
)

enum class ReadinessIssue(val blocking: Boolean) {
    NO_WORKPLACE(true),
    NO_OFFICE_WIFI(true), // mode needs Wi-Fi but no SSID saved
    NO_WORKING_DAYS(true),
    FINE_LOCATION(true),
    BACKGROUND_LOCATION(true),
    LOCATION_OFF(true),
    NOTIFICATIONS(false), // detection works; you just won't see arrivals / missed-entry prompts
    BATTERY_RESTRICTED(false), // works, but Samsung may delay or drop events
    NEARBY_WIFI(false),
    WIFI_OFF(false),
}

object SetupReadiness {
    fun issues(settings: AppSettings, workplaces: List<Workplace>, device: DeviceState): List<ReadinessIssue> = buildList {
        val mode = settings.detection.mode
        if (workplaces.isEmpty()) add(ReadinessIssue.NO_WORKPLACE)
        if (mode.usesWifi && !mode.usesGeofence && workplaces.none { it.ssids.isNotEmpty() }) add(ReadinessIssue.NO_OFFICE_WIFI)
        if (mode == com.arrivelog.domain.model.DetectionMode.GEOFENCE_AND_WIFI && workplaces.none { it.ssids.isNotEmpty() }) {
            add(ReadinessIssue.NO_OFFICE_WIFI)
        }
        if (!hasWorkingDay(settings)) add(ReadinessIssue.NO_WORKING_DAYS)
        if (!device.fineLocation) add(ReadinessIssue.FINE_LOCATION)
        if (!device.backgroundLocation) add(ReadinessIssue.BACKGROUND_LOCATION)
        if (!device.locationEnabled) add(ReadinessIssue.LOCATION_OFF)
        if (!device.notifications) add(ReadinessIssue.NOTIFICATIONS)
        if (!device.batteryUnrestricted) add(ReadinessIssue.BATTERY_RESTRICTED)
        if (mode.usesWifi && !device.nearbyWifi) add(ReadinessIssue.NEARBY_WIFI)
        if (mode.usesWifi && !device.wifiEnabled) add(ReadinessIssue.WIFI_OFF)
    }.distinct()

    fun isReady(settings: AppSettings, workplaces: List<Workplace>, device: DeviceState): Boolean =
        issues(settings, workplaces, device).none { it.blocking }

    private fun hasWorkingDay(settings: AppSettings): Boolean {
        // Any weekday resolving to a working profile (ignores one-off holidays).
        val monday = LocalDate.of(2001, 1, 1) // a Monday far from any holiday list
        return DayOfWeek.entries.any { d ->
            ScheduleResolver.resolve(monday.plusDays((d.value - 1).toLong()), settings.schedule.copy(daysOff = emptyMap())) is ResolvedDay.Working
        }
    }
}

/** "Would an arrival be detected if the window were open right now?" for the Test-now screen. */
object PresenceCheck {
    fun wouldDetect(
        mode: com.arrivelog.domain.model.DetectionMode,
        insideWorkplaceIds: Set<Long>,
        wifiWorkplaceIds: Set<Long>,
    ): Boolean = when (mode) {
        com.arrivelog.domain.model.DetectionMode.GEOFENCE_OR_WIFI -> insideWorkplaceIds.isNotEmpty() || wifiWorkplaceIds.isNotEmpty()
        com.arrivelog.domain.model.DetectionMode.GEOFENCE_AND_WIFI -> insideWorkplaceIds.any { it in wifiWorkplaceIds }
        com.arrivelog.domain.model.DetectionMode.WIFI_ONLY -> wifiWorkplaceIds.isNotEmpty()
        com.arrivelog.domain.model.DetectionMode.GEOFENCE_ONLY -> insideWorkplaceIds.isNotEmpty()
    }
}
