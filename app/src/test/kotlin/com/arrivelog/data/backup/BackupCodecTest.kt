package com.arrivelog.data.backup

import com.arrivelog.domain.model.AppSettings
import com.arrivelog.domain.model.DayStatus
import com.arrivelog.domain.model.DetectionMode
import com.arrivelog.domain.model.DetectionSettings
import com.arrivelog.domain.model.SignalSource
import com.arrivelog.domain.model.WorkDay
import com.arrivelog.domain.model.Workplace
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class BackupCodecTest {

    private val dubai = ZoneId.of("Asia/Dubai")
    private val contents = BackupContents(
        settings = AppSettings(detection = DetectionSettings(mode = DetectionMode.WIFI_ONLY, exitReminderEnabled = true), setupCompleted = true),
        workplaces = listOf(Workplace(id = 3, name = "Office", latitude = 25.2, longitude = 55.27, radiusMeters = 250, ssids = setOf("Corp"), bssids = setOf("aa:bb"))),
        workDays = listOf(
            WorkDay(
                date = LocalDate.of(2026, 10, 26),
                arrivalAt = Instant.parse("2026-10-26T04:07:00Z"),
                arrivalSource = SignalSource.GEOFENCE,
                arrivalWorkplaceId = 3,
                exitAt = Instant.parse("2026-10-26T12:30:00Z"),
                exitSource = SignalSource.MANUAL,
                zoneId = dubai,
                note = "badge \"reader\" down",
                exitEdited = true,
                originalArrivalAt = Instant.parse("2026-10-26T04:09:00Z"),
            ),
            WorkDay(date = LocalDate.of(2026, 10, 27), status = DayStatus.WFH),
        ),
    )

    @Test
    fun `round trip keeps settings, workplaces and every field of each day`() {
        val text = BackupCodec.encode(contents, Instant.parse("2026-10-28T10:00:00Z"))
        val back = BackupCodec.decode(text)
        assertThat(back.workDays).isEqualTo(contents.workDays)
        assertThat(back.workplaces).isEqualTo(contents.workplaces)
        assertThat(back.settings.detection.mode).isEqualTo(DetectionMode.WIFI_ONLY)
        assertThat(back.settings.detection.exitReminderEnabled).isTrue()
        assertThat(back.settings.setupCompleted).isTrue()
    }

    @Test
    fun `other JSON, a newer version and garbage are rejected`() {
        assertThrows(InvalidBackupException::class.java) { BackupCodec.decode("""{"hello":"world"}""") }
        assertThrows(InvalidBackupException::class.java) { BackupCodec.decode("not json") }
        val newer = BackupCodec.encode(contents, Instant.EPOCH).replace("\"version\": 1", "\"version\": 99")
        assertThrows(InvalidBackupException::class.java) { BackupCodec.decode(newer) }
    }

    @Test
    fun `an unreadable day is skipped, the rest is restored`() {
        val text = BackupCodec.encode(contents, Instant.EPOCH).replace("\"WFH\"", "\"NOT_A_STATUS\"")
        assertThat(BackupCodec.decode(text).workDays.map { it.date }).containsExactly(LocalDate.of(2026, 10, 26))
    }
}
