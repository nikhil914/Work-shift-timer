package com.arrivelog.domain.schedule

import com.arrivelog.domain.model.DayStatus
import com.arrivelog.domain.model.ScheduleProfile
import com.arrivelog.domain.model.ScheduleSettings
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

enum class ProfileProblem(val blocking: Boolean) {
    NAME_BLANK(true),
    WINDOW_ORDER(true),
    EXPECTED_ORDER(true),

    /** Expected start outside the entry window: allowed, but on-time arrivals would never be detected. */
    EXPECTED_START_OUTSIDE_WINDOW(false),
}

object ScheduleValidator {
    fun validate(name: String, entryStart: LocalTime, entryEnd: LocalTime, expectedStart: LocalTime, expectedEnd: LocalTime): Set<ProfileProblem> =
        buildSet {
            if (name.isBlank()) add(ProfileProblem.NAME_BLANK)
            if (!entryStart.isBefore(entryEnd)) add(ProfileProblem.WINDOW_ORDER)
            if (!expectedStart.isBefore(expectedEnd)) add(ProfileProblem.EXPECTED_ORDER)
            if (expectedStart.isBefore(entryStart) || !expectedStart.isBefore(entryEnd)) add(ProfileProblem.EXPECTED_START_OUTSIDE_WINDOW)
        }

    fun validate(p: ScheduleProfile): Set<ProfileProblem> =
        validate(p.name, p.entryWindowStart, p.entryWindowEnd, p.expectedStart, p.expectedEnd)
}

/** Pure edits on the schedule, so the screens stay thin and the rules are unit-tested. */
object ScheduleEditor {
    private val BUILT_IN = setOf(ScheduleProfile.ID_A, ScheduleProfile.ID_B)

    fun isBuiltIn(profileId: String): Boolean = profileId in BUILT_IN

    fun assign(s: ScheduleSettings, day: DayOfWeek, profileId: String?): ScheduleSettings =
        s.copy(assignments = if (profileId == null || s.profile(profileId) == null) s.assignments - day else s.assignments + (day to profileId))

    fun upsertProfile(s: ScheduleSettings, profile: ScheduleProfile): ScheduleSettings {
        val replaced = s.profiles.map { if (it.id == profile.id) profile else it }
        return s.copy(profiles = if (replaced.any { it.id == profile.id }) replaced else replaced + profile)
    }

    /** Built-in profiles stay; deleting a custom one turns the days that used it OFF. */
    fun deleteProfile(s: ScheduleSettings, profileId: String): ScheduleSettings =
        if (isBuiltIn(profileId)) {
            s
        } else {
            s.copy(
                profiles = s.profiles.filterNot { it.id == profileId },
                assignments = s.assignments.filterValues { it != profileId },
            )
        }

    fun newCustomProfileId(s: ScheduleSettings): String {
        var n = 1
        while (s.profiles.any { it.id == "C$n" }) n++
        return "C$n"
    }

    fun addDayOff(s: ScheduleSettings, date: LocalDate, status: DayStatus): ScheduleSettings {
        require(status == DayStatus.HOLIDAY || status == DayStatus.LEAVE) { "Only HOLIDAY or LEAVE" }
        return s.copy(daysOff = s.daysOff + (date to status))
    }

    /** Adds every date in [from]..[to] (inclusive), e.g. a week of leave. */
    fun addDayOffRange(s: ScheduleSettings, from: LocalDate, to: LocalDate, status: DayStatus): ScheduleSettings {
        var out = s
        var d = minOf(from, to)
        val end = maxOf(from, to)
        while (!d.isAfter(end)) {
            out = addDayOff(out, d, status)
            d = d.plusDays(1)
        }
        return out
    }

    fun removeDayOff(s: ScheduleSettings, date: LocalDate): ScheduleSettings = s.copy(daysOff = s.daysOff - date)
}
