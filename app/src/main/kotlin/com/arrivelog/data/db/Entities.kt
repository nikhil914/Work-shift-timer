package com.arrivelog.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.arrivelog.domain.model.DayStatus
import com.arrivelog.domain.model.RawEventType
import com.arrivelog.domain.model.SignalSource
import java.time.Instant
import java.time.LocalDate

/** One row per calendar day. The primary key on [date] is what makes arrival writes idempotent. */
@Entity(tableName = "work_day")
data class WorkDayEntity(
    @PrimaryKey val date: LocalDate,
    val status: DayStatus,
    val arrivalAt: Instant?,
    val arrivalSource: SignalSource?,
    val arrivalWorkplaceId: Long?,
    val exitAt: Instant?,
    val exitSource: SignalSource?,
    val zoneId: String?,
    val note: String?,
    val arrivalEdited: Boolean,
    val exitEdited: Boolean,
    val originalArrivalAt: Instant?,
    val originalExitAt: Instant?,
    val updatedAt: Instant,
)

@Entity(
    tableName = "event_log",
    indices = [Index("at"), Index("receivedAt")],
)
data class RawEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Instant,
    val receivedAt: Instant,
    val type: RawEventType,
    val workplaceId: Long?,
    val accuracyM: Float?,
    val ssid: String?,
    val bssid: String?,
    val accepted: Boolean,
    val reason: String,
)

@Entity(tableName = "workplace")
data class WorkplaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusM: Int,
    val ssids: Set<String>,
    val bssids: Set<String>,
)

/** Flattened DetectionState. Ephemeral per-day memory; old rows are pruned by the daily job. */
@Entity(tableName = "detection_state")
data class DetectionStateEntity(
    @PrimaryKey val date: LocalDate,
    val pendingEnterWorkplaceId: Long?,
    val pendingEnterAt: Instant?,
    val geoWorkplaceId: Long?,
    val geoSince: Instant?,
    val geoEstimated: Boolean,
    val wifiWorkplaceIds: Set<Long>?,
    val wifiSince: Instant?,
    val wifiSsid: String?,
    val wifiInRangeOnly: Boolean,
    val pendingExitAt: Instant?,
    val pendingExitSource: SignalSource?,
    val lastPresenceAt: Instant?,
    val ignoreEvidenceBefore: Instant?,
)
