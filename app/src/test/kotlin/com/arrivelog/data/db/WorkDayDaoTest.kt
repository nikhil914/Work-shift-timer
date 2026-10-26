package com.arrivelog.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arrivelog.domain.model.DayStatus
import com.arrivelog.domain.model.SignalSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class WorkDayDaoTest {

    private lateinit var db: ArriveLogDatabase
    private lateinit var dao: WorkDayDao

    private val date = LocalDate.of(2026, 10, 26)
    private fun t(hhmm: String): Instant = Instant.parse("2026-10-26T${hhmm}:00Z")
    private val now = t("12:00")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ArriveLogDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.workDayDao()
    }

    @After
    fun tearDown() = db.close()

    private fun sql(statement: String) = db.openHelper.writableDatabase.execSQL(statement)

    @Test
    fun recordArrival_isIdempotent() = runBlocking {
        assertThat(dao.recordArrival(date, t("04:07"), SignalSource.WIFI, 1, "Asia/Dubai", now)).isTrue()
        assertThat(dao.recordArrival(date, t("04:07"), SignalSource.WIFI, 1, "Asia/Dubai", now)).isFalse()
        val row = dao.get(date)
        assertThat(row?.arrivalAt).isEqualTo(t("04:07"))
        assertThat(row?.originalArrivalAt).isEqualTo(t("04:07"))
        assertThat(row?.zoneId).isEqualTo("Asia/Dubai")
        assertThat(row?.status).isEqualTo(DayStatus.WORKING)
        assertThat(dao.observeRange(date, date).first()).hasSize(1)
    }

    @Test
    fun laterArrival_neverOverwrites() = runBlocking {
        dao.recordArrival(date, t("04:07"), SignalSource.WIFI, 1, "Asia/Dubai", now)
        assertThat(dao.recordArrival(date, t("05:30"), SignalSource.GEOFENCE, 1, "Asia/Dubai", now)).isFalse()
        assertThat(dao.get(date)?.arrivalAt).isEqualTo(t("04:07"))
        assertThat(dao.get(date)?.arrivalSource).isEqualTo(SignalSource.WIFI)
    }

    @Test
    fun earlierAutoArrival_replacesUntouchedAutoArrival() = runBlocking {
        dao.recordArrival(date, t("04:07"), SignalSource.WIFI, 1, "Asia/Dubai", now)
        assertThat(dao.recordArrival(date, t("04:03"), SignalSource.GEOFENCE, 1, "Asia/Dubai", now)).isTrue()
        assertThat(dao.get(date)?.arrivalAt).isEqualTo(t("04:03"))
        assertThat(dao.get(date)?.arrivalSource).isEqualTo(SignalSource.GEOFENCE)
    }

    @Test
    fun earlierAutoArrival_neverReplacesManualOrEditedArrival() = runBlocking {
        dao.recordArrival(date, t("04:30"), SignalSource.MANUAL, null, "Asia/Dubai", now)
        assertThat(dao.recordArrival(date, t("04:00"), SignalSource.WIFI, 1, "Asia/Dubai", now)).isFalse()

        val other = date.plusDays(1)
        dao.recordArrival(other, Instant.parse("2026-10-27T04:30:00Z"), SignalSource.WIFI, 1, "Asia/Dubai", now)
        sql("UPDATE work_day SET arrivalEdited = 1 WHERE date = '2026-10-27'")
        assertThat(dao.recordArrival(other, Instant.parse("2026-10-27T04:00:00Z"), SignalSource.WIFI, 1, "Asia/Dubai", now)).isFalse()
    }

    @Test
    fun exit_requiresArrival_andLastExitWins() = runBlocking {
        assertThat(dao.recordExit(date, t("13:00"), SignalSource.GEOFENCE, now)).isEqualTo(0) // no row
        dao.recordArrival(date, t("04:00"), SignalSource.WIFI, 1, "Asia/Dubai", now)
        assertThat(dao.recordExit(date, t("03:59"), SignalSource.GEOFENCE, now)).isEqualTo(0) // before arrival
        assertThat(dao.recordExit(date, t("08:30"), SignalSource.GEOFENCE, now)).isEqualTo(1)
        assertThat(dao.recordExit(date, t("13:05"), SignalSource.WIFI, now)).isEqualTo(1)
        assertThat(dao.recordExit(date, t("12:00"), SignalSource.GEOFENCE, now)).isEqualTo(0) // earlier than stored
        assertThat(dao.get(date)?.exitAt).isEqualTo(t("13:05"))
        assertThat(dao.get(date)?.exitSource).isEqualTo(SignalSource.WIFI)
    }

    @Test
    fun manualExit_isNeverOverwritten_norReopened() = runBlocking {
        dao.recordArrival(date, t("04:00"), SignalSource.WIFI, 1, "Asia/Dubai", now)
        dao.recordExit(date, t("13:00"), SignalSource.GEOFENCE, now)
        sql("UPDATE work_day SET exitSource = 'MANUAL', exitEdited = 1 WHERE date = '2026-10-26'")
        assertThat(dao.recordExit(date, t("14:00"), SignalSource.GEOFENCE, now)).isEqualTo(0)
        assertThat(dao.reopenExit(date, t("15:00"), now)).isEqualTo(0)
        assertThat(dao.get(date)?.exitAt).isEqualTo(t("13:00"))
    }

    @Test
    fun reopenExit_clearsAutoExit_onlyForLaterReentry() = runBlocking {
        dao.recordArrival(date, t("04:00"), SignalSource.WIFI, 1, "Asia/Dubai", now)
        dao.recordExit(date, t("08:30"), SignalSource.GEOFENCE, now)
        assertThat(dao.reopenExit(date, t("08:00"), now)).isEqualTo(0)
        assertThat(dao.reopenExit(date, t("09:15"), now)).isEqualTo(1)
        val row = dao.get(date)
        assertThat(row?.exitAt).isNull()
        assertThat(row?.arrivalAt).isEqualTo(t("04:00"))
    }

    @Test
    fun markStatus_onlyAppliesToDaysWithoutArrival() = runBlocking {
        assertThat(dao.markStatus(date, DayStatus.WFH, now)).isTrue()
        assertThat(dao.get(date)?.status).isEqualTo(DayStatus.WFH)

        val other = date.plusDays(1)
        dao.recordArrival(other, Instant.parse("2026-10-27T04:00:00Z"), SignalSource.WIFI, 1, "Asia/Dubai", now)
        assertThat(dao.markStatus(other, DayStatus.LEAVE, now)).isFalse()
        assertThat(dao.get(other)?.status).isEqualTo(DayStatus.WORKING)
    }

    @Test
    fun manualArrival_onMarkedDay_turnsItIntoWorkingDay() = runBlocking {
        dao.markStatus(date, DayStatus.DAY_OFF, now)
        dao.recordArrival(date, t("06:00"), SignalSource.MANUAL, null, "Asia/Dubai", now)
        assertThat(dao.get(date)?.status).isEqualTo(DayStatus.WORKING)
    }

    @Test
    fun observeRange_isNewestFirst_andBounded() = runBlocking {
        (0L..9L).forEach { dao.markStatus(date.plusDays(it), DayStatus.WORKING, now) }
        val rows = dao.observeRange(date.plusDays(2), date.plusDays(5)).first()
        assertThat(rows.map { it.date }).containsExactly(
            date.plusDays(5), date.plusDays(4), date.plusDays(3), date.plusDays(2),
        ).inOrder()
    }
}
