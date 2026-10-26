package com.arrivelog.domain.model

import java.time.Instant
import java.time.LocalDate

/**
 * Per-day working memory of the engine, persisted between process deaths. Background events
 * arrive in separate short-lived receivers, so nothing here may live only in RAM.
 */
data class DetectionState(
    val date: LocalDate,
    /** ENTER seen, waiting for DWELL to confirm it was not a drive-by. */
    val pendingEnter: GeofenceEvidence? = null,
    /** DWELL-confirmed presence inside a workplace fence. */
    val geofencePresence: GeofenceEvidence? = null,
    val wifiPresence: WifiEvidence? = null,
    /** Exit seen, waiting out the debounce. */
    val pendingExit: PendingExit? = null,
    /** Latest presence-type event time; an EXIT older than this was delivered out of order. */
    val lastPresenceAt: Instant? = null,
    /** Set by Undo: evidence before this instant is not reused. */
    val ignoreEvidenceBefore: Instant? = null,
)

data class GeofenceEvidence(
    val workplaceId: Long,
    val since: Instant,
    /** True when DWELL arrived without a stored ENTER and the time was back-calculated. */
    val estimated: Boolean = false,
)

data class WifiEvidence(
    val workplaceIds: Set<Long>,
    val since: Instant,
    val ssid: String,
    val inRangeOnly: Boolean,
)

data class PendingExit(val at: Instant, val source: SignalSource)
