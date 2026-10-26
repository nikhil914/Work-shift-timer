package com.arrivelog.domain.model

enum class DetectionMode {
    /** Default: the first of geofence or Wi-Fi to confirm presence logs the arrival. */
    GEOFENCE_OR_WIFI,

    /** Stricter: both a confirmed geofence and office Wi-Fi are required. */
    GEOFENCE_AND_WIFI,
    WIFI_ONLY,
    GEOFENCE_ONLY,
    ;

    val usesGeofence: Boolean get() = this != WIFI_ONLY
    val usesWifi: Boolean get() = this != GEOFENCE_ONLY
}

/**
 * WEEKEND means "non-working day according to the weekly schedule" (any weekday set to OFF).
 * HOLIDAY / LEAVE come from the holidays-and-leave list. DAY_OFF / WFH / LEAVE can also be set
 * by the user for a single day (e.g. from the missed-entry notification).
 */
enum class DayStatus { WORKING, DAY_OFF, WFH, LEAVE, HOLIDAY, WEEKEND }

enum class SignalSource { GEOFENCE, WIFI, MANUAL }

enum class RawEventType {
    GEOFENCE_ENTER,
    GEOFENCE_DWELL,
    GEOFENCE_EXIT,
    WIFI_CONNECTED,
    WIFI_LOST,
    WIFI_IN_RANGE,
    MANUAL,

    /** Arm / disarm / missed-entry checks / errors. Counted as wake-ups in the battery audit. */
    SYSTEM,
}
