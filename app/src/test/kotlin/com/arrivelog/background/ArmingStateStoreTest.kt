package com.arrivelog.background

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arrivelog.domain.engine.GeofenceArming
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ArmingStateStoreTest {

    @Test
    fun roundTripsEveryField_andClearsNulls() = runBlocking {
        val store = ArmingStateStore(ApplicationProvider.getApplicationContext())
        val full = ArmingState(
            geofenceSignature = "ARRIVAL|180000|120000|1:24.45,54.37,200",
            geofenceArming = GeofenceArming.ARRIVAL,
            wifiArmed = true,
            lastRegisteredAt = Instant.parse("2026-10-27T02:15:00Z"),
            lastError = "GEOFENCE_NOT_AVAILABLE",
            lastErrorAt = Instant.parse("2026-10-27T02:16:00Z"),
            missedEntryCheckDate = LocalDate.of(2026, 10, 27),
            nextWakeAt = Instant.parse("2026-10-27T07:10:00Z"),
            nextWakeReason = "MISSED_ENTRY_CHECK",
            housekeepingDate = LocalDate.of(2026, 10, 27),
        )
        store.update { full }
        assertThat(store.get()).isEqualTo(full)

        store.update { it.copy(lastError = null, lastErrorAt = null, nextWakeAt = null, geofenceArming = GeofenceArming.OFF) }
        val cleared = store.get()
        assertThat(cleared.lastError).isNull()
        assertThat(cleared.nextWakeAt).isNull()
        assertThat(cleared.geofenceArming).isEqualTo(GeofenceArming.OFF)
        assertThat(cleared.wifiArmed).isTrue()
    }
}
