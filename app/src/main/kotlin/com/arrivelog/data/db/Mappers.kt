package com.arrivelog.data.db

import com.arrivelog.domain.model.DetectionState
import com.arrivelog.domain.model.GeofenceEvidence
import com.arrivelog.domain.model.PendingExit
import com.arrivelog.domain.model.RawEvent
import com.arrivelog.domain.model.WifiEvidence
import com.arrivelog.domain.model.WorkDay
import com.arrivelog.domain.model.Workplace
import java.time.DateTimeException
import java.time.ZoneId

fun WorkDayEntity.toDomain() = WorkDay(
    date = date,
    status = status,
    arrivalAt = arrivalAt,
    arrivalSource = arrivalSource,
    arrivalWorkplaceId = arrivalWorkplaceId,
    exitAt = exitAt,
    exitSource = exitSource,
    zoneId = zoneId?.let { id -> try { ZoneId.of(id) } catch (_: DateTimeException) { null } },
    note = note,
    arrivalEdited = arrivalEdited,
    exitEdited = exitEdited,
    originalArrivalAt = originalArrivalAt,
    originalExitAt = originalExitAt,
)

fun WorkDay.toEntity(updatedAt: java.time.Instant) = WorkDayEntity(
    date = date,
    status = status,
    arrivalAt = arrivalAt,
    arrivalSource = arrivalSource,
    arrivalWorkplaceId = arrivalWorkplaceId,
    exitAt = exitAt,
    exitSource = exitSource,
    zoneId = zoneId?.id,
    note = note,
    arrivalEdited = arrivalEdited,
    exitEdited = exitEdited,
    originalArrivalAt = originalArrivalAt,
    originalExitAt = originalExitAt,
    updatedAt = updatedAt,
)

fun RawEventEntity.toDomain() = RawEvent(
    id = id,
    at = at,
    receivedAt = receivedAt,
    type = type,
    workplaceId = workplaceId,
    accuracyM = accuracyM,
    ssid = ssid,
    bssid = bssid,
    accepted = accepted,
    reason = reason,
)

fun RawEvent.toEntity() = RawEventEntity(
    id = id,
    at = at,
    receivedAt = receivedAt,
    type = type,
    workplaceId = workplaceId,
    accuracyM = accuracyM,
    ssid = ssid,
    bssid = bssid,
    accepted = accepted,
    // Keep the debug log bounded even if a reason list grows.
    reason = reason.take(MAX_REASON_LENGTH),
)

fun WorkplaceEntity.toDomain() = Workplace(
    id = id,
    name = name,
    latitude = latitude,
    longitude = longitude,
    radiusMeters = Workplace.clampRadius(radiusM),
    ssids = ssids,
    bssids = bssids,
)

fun Workplace.toEntity() = WorkplaceEntity(
    id = id,
    name = name,
    latitude = latitude,
    longitude = longitude,
    radiusM = Workplace.clampRadius(radiusMeters),
    ssids = ssids,
    bssids = bssids.mapTo(mutableSetOf()) { it.lowercase() },
)

fun DetectionStateEntity.toDomain(): DetectionState {
    val pendingEnter = if (pendingEnterWorkplaceId != null && pendingEnterAt != null) {
        GeofenceEvidence(pendingEnterWorkplaceId, pendingEnterAt)
    } else {
        null
    }
    val geo = if (geoWorkplaceId != null && geoSince != null) {
        GeofenceEvidence(geoWorkplaceId, geoSince, geoEstimated)
    } else {
        null
    }
    val wifi = if (wifiSince != null && wifiSsid != null) {
        WifiEvidence(wifiWorkplaceIds.orEmpty(), wifiSince, wifiSsid, wifiInRangeOnly)
    } else {
        null
    }
    val exit = if (pendingExitAt != null && pendingExitSource != null) PendingExit(pendingExitAt, pendingExitSource) else null
    return DetectionState(
        date = date,
        pendingEnter = pendingEnter,
        geofencePresence = geo,
        wifiPresence = wifi,
        pendingExit = exit,
        lastPresenceAt = lastPresenceAt,
        ignoreEvidenceBefore = ignoreEvidenceBefore,
    )
}

fun DetectionState.toEntity() = DetectionStateEntity(
    date = date,
    pendingEnterWorkplaceId = pendingEnter?.workplaceId,
    pendingEnterAt = pendingEnter?.since,
    geoWorkplaceId = geofencePresence?.workplaceId,
    geoSince = geofencePresence?.since,
    geoEstimated = geofencePresence?.estimated ?: false,
    wifiWorkplaceIds = wifiPresence?.workplaceIds,
    wifiSince = wifiPresence?.since,
    wifiSsid = wifiPresence?.ssid,
    wifiInRangeOnly = wifiPresence?.inRangeOnly ?: false,
    pendingExitAt = pendingExit?.at,
    pendingExitSource = pendingExit?.source,
    lastPresenceAt = lastPresenceAt,
    ignoreEvidenceBefore = ignoreEvidenceBefore,
)

private const val MAX_REASON_LENGTH = 500
