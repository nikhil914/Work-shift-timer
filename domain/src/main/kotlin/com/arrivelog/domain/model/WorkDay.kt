package com.arrivelog.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class WorkDay(
    val date: LocalDate,
    val status: DayStatus = DayStatus.WORKING,
    val arrivalAt: Instant? = null,
    val arrivalSource: SignalSource? = null,
    val arrivalWorkplaceId: Long? = null,
    val exitAt: Instant? = null,
    val exitSource: SignalSource? = null,
    /**
     * Zone the arrival was recorded in. Times are stored as Instants; showing them in this zone
     * keeps "08:07" meaning 08:07 at the office even after a manual time-zone change.
     */
    val zoneId: ZoneId? = null,
    val note: String? = null,
    val arrivalEdited: Boolean = false,
    val exitEdited: Boolean = false,
    /** Audit trail: the time the app first recorded, before any manual edit. */
    val originalArrivalAt: Instant? = null,
    val originalExitAt: Instant? = null,
) {
    val editedManually: Boolean get() = arrivalEdited || exitEdited

    /** Auto detection may move an arrival earlier only if nobody has touched it by hand. */
    val arrivalIsAutoAndUntouched: Boolean
        get() = arrivalAt != null && !arrivalEdited && arrivalSource != SignalSource.MANUAL
}

data class RawEvent(
    val id: Long = 0,
    /** True event time (trigger location time / Wi-Fi callback time). */
    val at: Instant,
    /** When the app actually processed it. at vs receivedAt shows delivery delay in the debug log. */
    val receivedAt: Instant,
    val type: RawEventType,
    val workplaceId: Long? = null,
    val accuracyM: Float? = null,
    val ssid: String? = null,
    val bssid: String? = null,
    val accepted: Boolean,
    val reason: String,
)
