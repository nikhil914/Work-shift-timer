package com.arrivelog.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arrivelog.data.repository.RoomWorkplaceRepository
import com.arrivelog.domain.model.DetectionState
import com.arrivelog.domain.model.GeofenceEvidence
import com.arrivelog.domain.model.PendingExit
import com.arrivelog.domain.model.RawEvent
import com.arrivelog.domain.model.RawEventType
import com.arrivelog.domain.model.SignalSource
import com.arrivelog.domain.model.WifiEvidence
import com.arrivelog.domain.model.Workplace
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
class OtherDaosTest {

    private lateinit var db: ArriveLogDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ArriveLogDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun eventLog_ordersNewestFirst_countsAndPrunes() = runBlocking {
        val dao = db.rawEventDao()
        val base = Instant.parse("2026-10-26T04:00:00Z")
        (0L..4L).forEach { i ->
            dao.insert(
                RawEvent(
                    at = base.plusSeconds(i * 60),
                    receivedAt = base.plusSeconds(i * 60 + 90),
                    type = RawEventType.GEOFENCE_ENTER,
                    workplaceId = 1,
                    accuracyM = 18.5f,
                    ssid = null,
                    bssid = null,
                    accepted = i % 2 == 0L,
                    reason = "R$i",
                ).toEntity(),
            )
        }
        val recent = dao.observeRecent(3).first().map { it.toDomain() }
        assertThat(recent.map { it.reason }).containsExactly("R4", "R3", "R2").inOrder()
        assertThat(recent.first().accuracyM).isEqualTo(18.5f)
        assertThat(dao.countReceivedBetween(base, base.plusSeconds(200))).isEqualTo(2)
        assertThat(dao.deleteOlderThan(base.plusSeconds(200))).isEqualTo(2)
        assertThat(dao.between(base, base.plusSeconds(3600))).hasSize(3)
    }

    @Test
    fun workplace_roundTripsSsidsWithAnyCharacter_andClampsRadius() = runBlocking {
        val repo = RoomWorkplaceRepository(db.workplaceDao())
        val id = repo.upsert(
            Workplace(
                name = "HQ",
                latitude = 24.4539,
                longitude = 54.3773,
                radiusMeters = 100, // below the 150 m floor
                ssids = setOf("Corp, \"Guest\" | 5G", "مكتب"),
                bssids = setOf("AA:BB:CC:DD:EE:01"),
            ),
        )
        val stored = repo.getAll().single()
        assertThat(stored.id).isEqualTo(id)
        assertThat(stored.ssids).containsExactly("Corp, \"Guest\" | 5G", "مكتب")
        assertThat(stored.bssids).containsExactly("aa:bb:cc:dd:ee:01")
        assertThat(stored.radiusMeters).isEqualTo(Workplace.MIN_RADIUS_M)

        repo.upsert(stored.copy(name = "Main office"))
        assertThat(repo.getAll().single().name).isEqualTo("Main office")
        repo.delete(id)
        assertThat(repo.getAll()).isEmpty()
    }

    @Test
    fun detectionState_roundTripsEveryField_andPrunesOldDays() = runBlocking {
        val dao = db.detectionStateDao()
        val date = LocalDate.of(2026, 10, 26)
        val state = DetectionState(
            date = date,
            pendingEnter = GeofenceEvidence(2, Instant.parse("2026-10-26T04:01:00Z")),
            geofencePresence = GeofenceEvidence(1, Instant.parse("2026-10-26T04:00:00Z"), estimated = true),
            wifiPresence = WifiEvidence(setOf(1, 3), Instant.parse("2026-10-26T04:02:00Z"), "CorpWiFi", inRangeOnly = true),
            pendingExit = PendingExit(Instant.parse("2026-10-26T13:00:00Z"), SignalSource.WIFI),
            lastPresenceAt = Instant.parse("2026-10-26T12:00:00Z"),
            ignoreEvidenceBefore = Instant.parse("2026-10-26T03:00:00Z"),
        )
        dao.upsert(state.toEntity())
        assertThat(dao.get(date)?.toDomain()).isEqualTo(state)

        dao.upsert(DetectionState(date).toEntity())
        assertThat(dao.get(date)?.toDomain()).isEqualTo(DetectionState(date))

        dao.upsert(DetectionState(date.minusDays(3)).toEntity())
        dao.deleteBefore(date)
        assertThat(dao.get(date.minusDays(3))).isNull()
        assertThat(dao.get(date)).isNotNull()
    }
}
