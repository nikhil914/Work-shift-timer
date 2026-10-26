package com.arrivelog.domain.setup

import com.arrivelog.domain.geo.GeoMath
import com.arrivelog.domain.model.AppSettings
import com.arrivelog.domain.model.DayStatus
import com.arrivelog.domain.model.DetectionMode
import com.arrivelog.domain.model.ScheduleProfile
import com.arrivelog.domain.model.ScheduleSettings
import com.arrivelog.domain.schedule.ProfileProblem
import com.arrivelog.domain.schedule.ScheduleEditor
import com.arrivelog.domain.schedule.ScheduleValidator
import com.arrivelog.domain.testing.OFFICE
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class SetupHelpersTest {

    private val allGood = DeviceState(
        fineLocation = true,
        backgroundLocation = true,
        notifications = true,
        nearbyWifi = true,
        locationEnabled = true,
        batteryUnrestricted = true,
        wifiEnabled = true,
    )

    @Test
    fun `distance between two Abu Dhabi points`() {
        // Roughly 1.11 km per 0.01 deg latitude.
        val d = GeoMath.distanceMeters(24.4539, 54.3773, 24.4639, 54.3773)
        assertThat(d).isWithin(5.0).of(1112.0)
        assertThat(GeoMath.distanceMeters(24.4539, 54.3773, 24.4539, 54.3773)).isEqualTo(0.0)
        assertThat(GeoMath.isValidCoordinate(0.0, 0.0)).isFalse()
    }

    @Test
    fun `profile validation`() {
        fun t(h: Int, m: Int): LocalTime = LocalTime.of(h, m)
        assertThat(ScheduleValidator.validate(ScheduleProfile.DEFAULT_A)).isEmpty()
        assertThat(ScheduleValidator.validate(" ", t(6, 30), t(6, 0), t(8, 0), t(7, 0)))
            .containsExactly(ProfileProblem.NAME_BLANK, ProfileProblem.WINDOW_ORDER, ProfileProblem.EXPECTED_ORDER, ProfileProblem.EXPECTED_START_OUTSIDE_WINDOW)
        assertThat(ScheduleValidator.validate("x", t(6, 30), t(11, 0), t(12, 0), t(17, 0)))
            .containsExactly(ProfileProblem.EXPECTED_START_OUTSIDE_WINDOW)
    }

    @Test
    fun `deleting a custom profile turns its days off, built-ins cannot be deleted`() {
        val custom = ScheduleProfile("C1", "Ramadan", LocalTime.of(8, 0), LocalTime.of(12, 0), LocalTime.of(9, 0), LocalTime.of(15, 0))
        var s = ScheduleEditor.upsertProfile(ScheduleSettings(), custom)
        s = ScheduleEditor.assign(s, DayOfWeek.SUNDAY, "C1")
        assertThat(ScheduleEditor.newCustomProfileId(s)).isEqualTo("C2")
        s = ScheduleEditor.deleteProfile(s, "C1")
        assertThat(s.assignments).doesNotContainKey(DayOfWeek.SUNDAY)
        assertThat(ScheduleEditor.deleteProfile(s, ScheduleProfile.ID_A)).isEqualTo(s)
        assertThat(ScheduleEditor.assign(s, DayOfWeek.MONDAY, null).assignments).doesNotContainKey(DayOfWeek.MONDAY)
    }

    @Test
    fun `leave range adds every day`() {
        val s = ScheduleEditor.addDayOffRange(ScheduleSettings(), LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 3), DayStatus.LEAVE)
        assertThat(s.daysOff.keys).containsExactly(LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 4), LocalDate.of(2026, 11, 5))
        assertThat(ScheduleEditor.removeDayOff(s, LocalDate.of(2026, 11, 4)).daysOff).hasSize(2)
    }

    @Test
    fun `readiness - fresh install lists what is missing`() {
        val issues = SetupReadiness.issues(AppSettings(), emptyList(), allGood.copy(backgroundLocation = false, notifications = false))
        assertThat(issues).containsExactly(ReadinessIssue.NO_WORKPLACE, ReadinessIssue.BACKGROUND_LOCATION, ReadinessIssue.NOTIFICATIONS)
        assertThat(SetupReadiness.isReady(AppSettings(), listOf(OFFICE), allGood)).isTrue()
    }

    @Test
    fun `readiness - wifi-only without an SSID and an empty week are blocking`() {
        val s = AppSettings().let { it.copy(detection = it.detection.copy(mode = DetectionMode.WIFI_ONLY), schedule = it.schedule.copy(assignments = emptyMap())) }
        val issues = SetupReadiness.issues(s, listOf(OFFICE.copy(ssids = emptySet())), allGood)
        assertThat(issues).containsExactly(ReadinessIssue.NO_OFFICE_WIFI, ReadinessIssue.NO_WORKING_DAYS)
        assertThat(SetupReadiness.isReady(s, listOf(OFFICE), allGood)).isFalse()
    }

    @Test
    fun `presence check per mode`() {
        assertThat(PresenceCheck.wouldDetect(DetectionMode.GEOFENCE_OR_WIFI, emptySet(), setOf(1))).isTrue()
        assertThat(PresenceCheck.wouldDetect(DetectionMode.GEOFENCE_AND_WIFI, setOf(1), setOf(2))).isFalse()
        assertThat(PresenceCheck.wouldDetect(DetectionMode.GEOFENCE_AND_WIFI, setOf(1), setOf(1, 2))).isTrue()
        assertThat(PresenceCheck.wouldDetect(DetectionMode.WIFI_ONLY, setOf(1), emptySet())).isFalse()
    }
}
