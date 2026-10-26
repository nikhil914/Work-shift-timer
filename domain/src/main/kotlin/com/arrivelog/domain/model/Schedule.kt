package com.arrivelog.domain.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

data class ScheduleProfile(
    val id: String,
    val name: String,
    val entryWindowStart: LocalTime,
    val entryWindowEnd: LocalTime,
    val expectedStart: LocalTime,
    val expectedEnd: LocalTime,
) {
    /** Windows may not cross midnight; the setup UI enforces this, the resolver double-checks. */
    val isValid: Boolean
        get() = entryWindowStart.isBefore(entryWindowEnd) && expectedStart.isBefore(expectedEnd)

    companion object {
        const val ID_A = "A"
        const val ID_B = "B"

        val DEFAULT_A = ScheduleProfile(
            id = ID_A,
            name = "Profile A",
            entryWindowStart = LocalTime.of(6, 30),
            entryWindowEnd = LocalTime.of(11, 0),
            expectedStart = LocalTime.of(8, 0),
            expectedEnd = LocalTime.of(17, 0),
        )

        val DEFAULT_B = ScheduleProfile(
            id = ID_B,
            name = "Profile B",
            entryWindowStart = LocalTime.of(6, 30),
            entryWindowEnd = LocalTime.of(10, 0),
            expectedStart = LocalTime.of(7, 30),
            expectedEnd = LocalTime.of(12, 0),
        )
    }
}

data class ScheduleSettings(
    val profiles: List<ScheduleProfile> = listOf(ScheduleProfile.DEFAULT_A, ScheduleProfile.DEFAULT_B),
    /** Weekday → profile id. A weekday that is absent is OFF (no detection work at all). */
    val assignments: Map<DayOfWeek, String> = DEFAULT_ASSIGNMENTS,
    /** Holidays and leave: dates where detection is skipped. Values are HOLIDAY or LEAVE. */
    val daysOff: Map<LocalDate, DayStatus> = emptyMap(),
) {
    fun profile(id: String?): ScheduleProfile? = id?.let { wanted -> profiles.firstOrNull { it.id == wanted } }

    companion object {
        val DEFAULT_ASSIGNMENTS: Map<DayOfWeek, String> = mapOf(
            DayOfWeek.MONDAY to ScheduleProfile.ID_A,
            DayOfWeek.TUESDAY to ScheduleProfile.ID_A,
            DayOfWeek.WEDNESDAY to ScheduleProfile.ID_A,
            DayOfWeek.THURSDAY to ScheduleProfile.ID_A,
            DayOfWeek.FRIDAY to ScheduleProfile.ID_B,
        )
    }
}
