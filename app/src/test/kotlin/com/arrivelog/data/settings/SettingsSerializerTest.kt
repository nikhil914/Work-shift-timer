package com.arrivelog.data.settings

import androidx.datastore.core.CorruptionException
import com.arrivelog.domain.model.AppSettings
import com.arrivelog.domain.model.DayStatus
import com.arrivelog.domain.model.DetectionMode
import com.arrivelog.domain.model.ScheduleProfile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/** Plain JVM test: the serializer and mappers have no Android dependency. */
class SettingsSerializerTest {

    private fun roundTrip(dto: SettingsDto): SettingsDto = runBlocking {
        val out = ByteArrayOutputStream()
        SettingsSerializer.writeTo(dto, out)
        SettingsSerializer.readFrom(ByteArrayInputStream(out.toByteArray()))
    }

    private fun read(json: String): SettingsDto = runBlocking {
        SettingsSerializer.readFrom(ByteArrayInputStream(json.encodeToByteArray()))
    }

    @Test
    fun defaults_matchTheSpec() {
        val settings = SettingsSerializer.defaultValue.toDomain()
        assertThat(settings.detection.mode).isEqualTo(DetectionMode.GEOFENCE_OR_WIFI)
        assertThat(settings.detection.exitTrackingEnabled).isTrue()
        assertThat(settings.detection.exitReminderEnabled).isFalse()
        assertThat(settings.use24HourClock).isTrue()
        assertThat(settings.schedule.assignments[DayOfWeek.FRIDAY]).isEqualTo(ScheduleProfile.ID_B)
        assertThat(settings.schedule.assignments).doesNotContainKey(DayOfWeek.SATURDAY)
        assertThat(settings.schedule.assignments).doesNotContainKey(DayOfWeek.SUNDAY)
    }

    @Test
    fun fullSettings_surviveARoundTrip() {
        val custom = ScheduleProfile("C", "Ramadan", LocalTime.of(8, 0), LocalTime.of(12, 0), LocalTime.of(9, 0), LocalTime.of(15, 0))
        val original = AppSettings(setupCompleted = true).let { s ->
            s.copy(
                detection = s.detection.copy(mode = DetectionMode.GEOFENCE_AND_WIFI, matchBssid = true, fasterDetection = true),
                schedule = s.schedule.copy(
                    profiles = s.schedule.profiles + custom,
                    assignments = s.schedule.assignments + (DayOfWeek.SUNDAY to "C"),
                    daysOff = mapOf(LocalDate.of(2026, 12, 2) to DayStatus.HOLIDAY, LocalDate.of(2026, 11, 5) to DayStatus.LEAVE),
                ),
            )
        }
        assertThat(roundTrip(original.toDto()).toDomain()).isEqualTo(original)
    }

    @Test
    fun garbage_isReportedAsCorruption() {
        assertThrows(CorruptionException::class.java) { read("{ not json") }
    }

    @Test
    fun unknownKeysAndValues_fallBackSafely() {
        val dto = read(
            """
            {
              "schemaVersion": 9,
              "someFutureField": {"x": 1},
              "detection": {"mode": "TELEPATHY", "exitTrackingEnabled": false},
              "schedule": {
                "profiles": [
                  {"id":"A","name":"A","entryWindowStart":"06:30","entryWindowEnd":"11:00","expectedStart":"08:00","expectedEnd":"17:00"},
                  {"id":"X","name":"broken","entryWindowStart":"nope","entryWindowEnd":"11:00","expectedStart":"08:00","expectedEnd":"17:00"}
                ],
                "assignments": {"MONDAY":"A","TUESDAY":"X","FUNDAY":"A"},
                "daysOff": {"2026-12-02":"HOLIDAY","not-a-date":"LEAVE","2026-12-03":"WEEKEND"}
              }
            }
            """.trimIndent(),
        )
        val settings = dto.toDomain()
        assertThat(settings.detection.mode).isEqualTo(DetectionMode.GEOFENCE_OR_WIFI)
        assertThat(settings.detection.exitTrackingEnabled).isFalse()
        assertThat(settings.schedule.profiles.map { it.id }).containsExactly("A")
        assertThat(settings.schedule.assignments).containsExactly(DayOfWeek.MONDAY, "A")
        assertThat(settings.schedule.daysOff).containsExactly(LocalDate.of(2026, 12, 2), DayStatus.HOLIDAY)
    }
}
