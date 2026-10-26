package com.arrivelog.data.settings

import com.arrivelog.domain.model.AppSettings
import com.arrivelog.domain.model.DayStatus
import com.arrivelog.domain.model.DetectionMode
import com.arrivelog.domain.model.DetectionSettings
import com.arrivelog.domain.model.ScheduleProfile
import com.arrivelog.domain.model.ScheduleSettings
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * On-disk shape of the settings (typed DataStore, JSON). Kept separate from the domain models so
 * the file format can evolve with [schemaVersion]. Every field has a default, and enums/times are
 * stored as strings parsed defensively, so an older or newer file never crashes the app.
 */
@Serializable
data class SettingsDto(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val setupCompleted: Boolean = false,
    val use24HourClock: Boolean = true,
    val detection: DetectionDto = DetectionDto(),
    val schedule: ScheduleDto = ScheduleDto(),
) {
    companion object {
        const val CURRENT_SCHEMA = 1
    }
}

@Serializable
data class DetectionDto(
    val mode: String = DetectionMode.GEOFENCE_OR_WIFI.name,
    val exitTrackingEnabled: Boolean = true,
    val exitReminderEnabled: Boolean = false,
    val wifiInRangeCountsAsPresent: Boolean = false,
    val matchBssid: Boolean = false,
    val fasterDetection: Boolean = false,
    val gpsConfirmation: Boolean = false,
)

@Serializable
data class ScheduleDto(
    val profiles: List<ProfileDto> = listOf(ScheduleProfile.DEFAULT_A.toDto(), ScheduleProfile.DEFAULT_B.toDto()),
    /** "MONDAY" -> profile id. Missing weekday = OFF. */
    val assignments: Map<String, String> = ScheduleSettings.DEFAULT_ASSIGNMENTS.mapKeys { it.key.name },
    /** "2026-12-02" -> "HOLIDAY" | "LEAVE". */
    val daysOff: Map<String, String> = emptyMap(),
)

@Serializable
data class ProfileDto(
    val id: String,
    val name: String,
    val entryWindowStart: String,
    val entryWindowEnd: String,
    val expectedStart: String,
    val expectedEnd: String,
)

// region mapping

fun AppSettings.toDto() = SettingsDto(
    setupCompleted = setupCompleted,
    use24HourClock = use24HourClock,
    detection = DetectionDto(
        mode = detection.mode.name,
        exitTrackingEnabled = detection.exitTrackingEnabled,
        exitReminderEnabled = detection.exitReminderEnabled,
        wifiInRangeCountsAsPresent = detection.wifiInRangeCountsAsPresent,
        matchBssid = detection.matchBssid,
        fasterDetection = detection.fasterDetection,
        gpsConfirmation = detection.gpsConfirmation,
    ),
    schedule = ScheduleDto(
        profiles = schedule.profiles.map { it.toDto() },
        assignments = schedule.assignments.mapKeys { it.key.name },
        daysOff = schedule.daysOff.entries.associate { it.key.toString() to it.value.name },
    ),
)

fun SettingsDto.toDomain(): AppSettings {
    val defaults = AppSettings()
    val profiles = schedule.profiles.mapNotNull { it.toDomainOrNull() }.ifEmpty { defaults.schedule.profiles }
    return AppSettings(
        setupCompleted = setupCompleted,
        use24HourClock = use24HourClock,
        detection = DetectionSettings(
            mode = enumOrDefault(detection.mode, defaults.detection.mode),
            exitTrackingEnabled = detection.exitTrackingEnabled,
            exitReminderEnabled = detection.exitReminderEnabled,
            wifiInRangeCountsAsPresent = detection.wifiInRangeCountsAsPresent,
            matchBssid = detection.matchBssid,
            fasterDetection = detection.fasterDetection,
            gpsConfirmation = detection.gpsConfirmation,
        ),
        schedule = ScheduleSettings(
            profiles = profiles,
            assignments = schedule.assignments.mapNotNull { (day, id) ->
                val weekday = runCatching { DayOfWeek.valueOf(day) }.getOrNull()
                weekday?.takeIf { profiles.any { p -> p.id == id } }?.let { it to id }
            }.toMap(),
            daysOff = schedule.daysOff.mapNotNull { (date, status) ->
                val d = runCatching { LocalDate.parse(date) }.getOrNull()
                val s = runCatching { DayStatus.valueOf(status) }.getOrNull()
                if (d != null && (s == DayStatus.HOLIDAY || s == DayStatus.LEAVE)) d to s else null
            }.toMap(),
        ),
    )
}

private fun ScheduleProfile.toDto() = ProfileDto(
    id = id,
    name = name,
    entryWindowStart = entryWindowStart.toString(),
    entryWindowEnd = entryWindowEnd.toString(),
    expectedStart = expectedStart.toString(),
    expectedEnd = expectedEnd.toString(),
)

private fun ProfileDto.toDomainOrNull(): ScheduleProfile? = runCatching {
    ScheduleProfile(
        id = id,
        name = name,
        entryWindowStart = LocalTime.parse(entryWindowStart),
        entryWindowEnd = LocalTime.parse(entryWindowEnd),
        expectedStart = LocalTime.parse(expectedStart),
        expectedEnd = LocalTime.parse(expectedEnd),
    )
}.getOrNull()

private inline fun <reified E : Enum<E>> enumOrDefault(name: String, default: E): E =
    runCatching { enumValueOf<E>(name) }.getOrDefault(default)

// endregion
