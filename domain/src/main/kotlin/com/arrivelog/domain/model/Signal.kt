package com.arrivelog.domain.model

import java.time.Instant

data class WifiNetwork(val ssid: String?, val bssid: String?)

/**
 * Everything the background layer can tell the engine. [at] is always the true event time;
 * [receivedAt] is when the app got to process it.
 */
sealed interface Signal {
    val at: Instant
    val receivedAt: Instant

    sealed interface Geofence : Signal {
        val workplaceId: Long
        val accuracyM: Float?
    }

    sealed interface Wifi : Signal

    data class GeofenceEnter(
        override val workplaceId: Long,
        override val at: Instant,
        override val receivedAt: Instant = at,
        override val accuracyM: Float? = null,
    ) : Geofence

    data class GeofenceDwell(
        override val workplaceId: Long,
        override val at: Instant,
        override val receivedAt: Instant = at,
        override val accuracyM: Float? = null,
    ) : Geofence

    data class GeofenceExit(
        override val workplaceId: Long,
        override val at: Instant,
        override val receivedAt: Instant = at,
        override val accuracyM: Float? = null,
        /**
         * The Wi-Fi network the phone is connected to when the EXIT is processed (a free read).
         * If it is office Wi-Fi the EXIT is indoor GPS jitter and is ignored.
         */
        val connectedWifi: WifiNetwork? = null,
    ) : Geofence

    data class WifiConnected(
        val network: WifiNetwork,
        override val at: Instant,
        override val receivedAt: Instant = at,
    ) : Wifi

    data class WifiLost(
        val network: WifiNetwork?,
        override val at: Instant,
        override val receivedAt: Instant = at,
    ) : Wifi

    /** Cached scan results, read only when already awake (geofence event or app open). */
    data class WifiInRange(
        val networks: List<WifiNetwork>,
        override val at: Instant,
        override val receivedAt: Instant = at,
    ) : Wifi

    /** "Log now", "Pick time", QS tile. */
    data class Manual(
        override val at: Instant,
        override val receivedAt: Instant = at,
    ) : Signal
}

val Signal.rawEventType: RawEventType
    get() = when (this) {
        is Signal.GeofenceEnter -> RawEventType.GEOFENCE_ENTER
        is Signal.GeofenceDwell -> RawEventType.GEOFENCE_DWELL
        is Signal.GeofenceExit -> RawEventType.GEOFENCE_EXIT
        is Signal.WifiConnected -> RawEventType.WIFI_CONNECTED
        is Signal.WifiLost -> RawEventType.WIFI_LOST
        is Signal.WifiInRange -> RawEventType.WIFI_IN_RANGE
        is Signal.Manual -> RawEventType.MANUAL
    }
