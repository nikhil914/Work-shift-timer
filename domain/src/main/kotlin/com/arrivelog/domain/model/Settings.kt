package com.arrivelog.domain.model

import java.time.Duration

data class DetectionSettings(
    val mode: DetectionMode = DetectionMode.GEOFENCE_OR_WIFI,
    val exitTrackingEnabled: Boolean = true,
    val exitReminderEnabled: Boolean = false,
    /** "Count as present when the SSID is in range, even if not connected". */
    val wifiInRangeCountsAsPresent: Boolean = false,
    val matchBssid: Boolean = false,
    /** "Faster detection — uses more battery": geofence responsiveness 60 s instead of 180 s. */
    val fasterDetection: Boolean = false,
    /** Optional single balanced-power fix to confirm a geofence event. Off by default. */
    val gpsConfirmation: Boolean = false,
)

data class AppSettings(
    val detection: DetectionSettings = DetectionSettings(),
    val schedule: ScheduleSettings = ScheduleSettings(),
    val setupCompleted: Boolean = false,
    val use24HourClock: Boolean = true,
)

/**
 * Fixed timing constants. Not user-editable; injected so tests can see and vary them.
 */
data class DetectionTuning(
    /** Geofence loitering delay used for DWELL (drive-by protection). */
    val loiteringDelay: Duration = Duration.ofMinutes(2),
    /** Exit is only committed after being away this long; any re-entry cancels it. */
    val exitDebounce: Duration = Duration.ofMinutes(10),
    /** In AND mode, two signals further apart than this are not treated as the same arrival. */
    val andCorrelationWindow: Duration = Duration.ofMinutes(60),
    /** Geofences/Wi-Fi callback are registered this long before the entry window opens. */
    val armLeadTime: Duration = Duration.ofMinutes(15),
    /** Arrivals stamped within this of arm time are flagged estimated (already inside when armed). */
    val armSettleMargin: Duration = Duration.ofMinutes(5),
    /**
     * Listeners stay up this long after the window closes, and the missed-entry check runs at
     * windowEnd + grace, so an in-window ENTER whose DWELL is delivered late still lands.
     * (Loitering 2 min + 180 s responsiveness + margin.)
     */
    val windowCloseGrace: Duration = Duration.ofMinutes(10),
    val missedEntryRenotifyAfter: Duration = Duration.ofMinutes(60),
    val exitReminderAfterExpectedEnd: Duration = Duration.ofHours(2),
    val disarmAfterExpectedEnd: Duration = Duration.ofHours(3),
    val responsivenessDefaultMs: Int = 180_000,
    val responsivenessFastMs: Int = 60_000,
)
