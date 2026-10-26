package com.arrivelog.domain.engine

import com.arrivelog.domain.model.DetectionMode
import com.arrivelog.domain.model.SignalSource
import com.arrivelog.domain.model.WifiNetwork
import com.arrivelog.domain.testing.EngineHarness
import com.arrivelog.domain.testing.MON
import com.arrivelog.domain.testing.OFFICE
import com.arrivelog.domain.testing.OFFICE_BSSID
import com.arrivelog.domain.testing.OFFICE_ID
import com.arrivelog.domain.testing.OFFICE_SSID
import com.arrivelog.domain.testing.SITE2
import com.arrivelog.domain.testing.SITE2_ID
import com.arrivelog.domain.testing.at
import com.arrivelog.domain.testing.homeWifi
import com.arrivelog.domain.testing.officeWifi
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DetectionModesTest {

    private val h = EngineHarness()

    // OR (default) ---------------------------------------------------------------------------

    @Test
    fun `OR - office wifi alone records the arrival at the callback time`() {
        val d = h.wifi(MON.at(8, 7), receivedAt = MON.at(8, 7))
        assertThat(d.arrival).isEqualTo(
            Outcome.ArrivalRecorded(MON.at(8, 7), SignalSource.WIFI, OFFICE_ID, estimated = false, replacesLaterRecord = false),
        )
    }

    @Test
    fun `OR - ENTER alone waits for DWELL, DWELL stores the ENTER time`() {
        val enter = h.enter(MON.at(8, 2), receivedAt = MON.at(8, 5))
        assertThat(enter.outcomes.single()).isEqualTo(Outcome.Noted(DecisionReason.AWAITING_DWELL))
        assertThat(h.day(MON)).isNull()

        val dwell = h.dwell(MON.at(8, 4), receivedAt = MON.at(8, 9))
        assertThat(dwell.arrival?.at).isEqualTo(MON.at(8, 2))
        assertThat(dwell.arrival?.estimated).isFalse()
    }

    @Test
    fun `OR - drive-by ENTER then EXIT never logs an arrival`() {
        h.enter(MON.at(7, 40))
        h.exit(MON.at(7, 41))
        assertThat(h.day(MON)).isNull()
        assertThat(h.states[MON]?.pendingEnter).isNull()
    }

    @Test
    fun `OR - pre-window presence does not poison a real in-window arrival`() {
        // Drive-by at 06:10 with DWELL (traffic jam), EXIT lost. Real arrival on Wi-Fi at 08:05.
        h.arriveByGeofence(MON.at(6, 10))
        assertThat(h.day(MON)).isNull()
        val d = h.wifi(MON.at(8, 5))
        assertThat(d.arrival?.at).isEqualTo(MON.at(8, 5))
        assertThat(d.arrival?.source).isEqualTo(SignalSource.WIFI)
    }

    @Test
    fun `OR - DWELL without a stored ENTER back-calculates the entry and flags it`() {
        val d = h.dwell(MON.at(8, 10))
        assertThat(d.arrival?.at).isEqualTo(MON.at(8, 8))
        assertThat(d.arrival?.estimated).isTrue()
    }

    @Test
    fun `OR - home wifi is ignored`() {
        val d = h.wifi(MON.at(7, 0), network = homeWifi)
        assertThat(d.outcomes.single()).isEqualTo(Outcome.Ignored(DecisionReason.SSID_NOT_OFFICE))
    }

    @Test
    fun `OR - redacted SSID is logged as SSID_UNKNOWN so Health can flag the permission`() {
        val d = h.wifi(MON.at(8, 0), network = WifiNetwork("<unknown ssid>", null))
        assertThat(d.outcomes.single()).isEqualTo(Outcome.Ignored(DecisionReason.SSID_UNKNOWN))
    }

    // AND ------------------------------------------------------------------------------------

    @Test
    fun `AND - wifi alone waits for the geofence`() {
        h.mode(DetectionMode.GEOFENCE_AND_WIFI)
        val d = h.wifi(MON.at(8, 4))
        assertThat(d.outcomes.single()).isEqualTo(Outcome.Noted(DecisionReason.AWAITING_GEOFENCE))
        assertThat(h.day(MON)).isNull()
    }

    @Test
    fun `AND - geofence alone waits for wifi`() {
        h.mode(DetectionMode.GEOFENCE_AND_WIFI)
        val d = h.arriveByGeofence(MON.at(8, 2))
        assertThat(d.outcomes.single()).isEqualTo(Outcome.Noted(DecisionReason.AWAITING_WIFI))
    }

    @Test
    fun `AND - both within the correlation window log the earlier true time`() {
        h.mode(DetectionMode.GEOFENCE_AND_WIFI)
        h.arriveByGeofence(MON.at(8, 2))
        val d = h.wifi(MON.at(8, 6))
        assertThat(d.arrival?.at).isEqualTo(MON.at(8, 2))
        assertThat(d.arrival?.source).isEqualTo(SignalSource.GEOFENCE)
        assertThat(d.arrival?.workplaceId).isEqualTo(OFFICE_ID)
    }

    @Test
    fun `AND - signals too far apart log the moment both became true`() {
        h.mode(DetectionMode.GEOFENCE_AND_WIFI)
        h.wifi(MON.at(6, 45)) // stale (e.g. passed the building, Wi-Fi-lost never delivered)
        h.arriveByGeofence(MON.at(8, 0))
        // 75 min apart: the 06:45 Wi-Fi is not part of this arrival; both hold from the 08:00 ENTER.
        assertThat(h.day(MON)?.arrivalAt).isEqualTo(MON.at(8, 0))
    }

    @Test
    fun `AND - geofence before arm time plus wifi later logs the wifi time`() {
        h.mode(DetectionMode.GEOFENCE_AND_WIFI)
        h.arriveByGeofence(MON.at(6, 5))
        val d = h.wifi(MON.at(6, 40))
        assertThat(d.arrival?.at).isEqualTo(MON.at(6, 40))
    }

    @Test
    fun `AND - geofence of one site and wifi of another do not combine`() {
        h.workplaces = listOf(OFFICE, SITE2)
        h.mode(DetectionMode.GEOFENCE_AND_WIFI)
        h.arriveByGeofence(MON.at(8, 0), workplaceId = SITE2_ID)
        val d = h.wifi(MON.at(8, 5))
        assertThat(d.outcomes.single()).isEqualTo(Outcome.Noted(DecisionReason.WORKPLACE_MISMATCH))
    }

    // Single-signal modes ----------------------------------------------------------------------

    @Test
    fun `WIFI_ONLY ignores geofence events`() {
        h.mode(DetectionMode.WIFI_ONLY)
        val d = h.arriveByGeofence(MON.at(8, 0))
        assertThat(d.outcomes.single()).isEqualTo(Outcome.Ignored(DecisionReason.MODE_EXCLUDES_SIGNAL))
        assertThat(h.wifi(MON.at(8, 5)).arrival?.at).isEqualTo(MON.at(8, 5))
    }

    @Test
    fun `WIFI_ONLY still uses the geofence for the exit once arrived`() {
        h.mode(DetectionMode.WIFI_ONLY)
        h.wifi(MON.at(8, 0))
        assertThat(h.exit(MON.at(17, 0)).outcomes.single()).isEqualTo(Outcome.Noted(DecisionReason.EXIT_PENDING))
        h.settle(MON, MON.at(17, 30))
        assertThat(h.day(MON)?.exitAt).isEqualTo(MON.at(17, 0))
        assertThat(h.day(MON)?.exitSource).isEqualTo(SignalSource.GEOFENCE)
    }

    @Test
    fun `WIFI_ONLY geofence re-entry does not move the arrival`() {
        h.mode(DetectionMode.WIFI_ONLY)
        h.wifi(MON.at(8, 30))
        h.arriveByGeofence(MON.at(8, 10))
        assertThat(h.day(MON)?.arrivalAt).isEqualTo(MON.at(8, 30))
    }

    @Test
    fun `GEOFENCE_ONLY ignores wifi`() {
        h.mode(DetectionMode.GEOFENCE_ONLY)
        assertThat(h.wifi(MON.at(8, 0)).outcomes.single()).isEqualTo(Outcome.Ignored(DecisionReason.MODE_EXCLUDES_SIGNAL))
        assertThat(h.arriveByGeofence(MON.at(8, 3)).arrival?.at).isEqualTo(MON.at(8, 3))
    }

    @Test
    fun `GEOFENCE_ONLY - a lost EXIT before the window does not block the real entry`() {
        h.mode(DetectionMode.GEOFENCE_ONLY)
        h.arriveByGeofence(MON.at(6, 5)) // drive-by, EXIT never delivered
        val d = h.arriveByGeofence(MON.at(8, 0))
        assertThat(d.arrival?.at).isEqualTo(MON.at(8, 0))
    }

    @Test
    fun `WIFI_ONLY - a reconnect inside the window counts even if connected before it`() {
        h.mode(DetectionMode.WIFI_ONLY)
        assertThat(h.wifi(MON.at(6, 5)).outcomes.single().reason).isEqualTo(DecisionReason.BEFORE_WINDOW)
        assertThat(h.wifi(MON.at(8, 5)).arrival?.at).isEqualTo(MON.at(8, 5))
    }

    @Test
    fun `unknown geofence id is ignored`() {
        val d = h.dwell(MON.at(8, 0), workplaceId = 99)
        assertThat(d.outcomes.single()).isEqualTo(Outcome.Ignored(DecisionReason.UNKNOWN_WORKPLACE))
    }

    // Wi-Fi options ------------------------------------------------------------------------------

    @Test
    fun `strict BSSID matching rejects a different access point with the same SSID`() {
        h.settings = h.settings.copy(matchBssid = true)
        val rogue = WifiNetwork("\"$OFFICE_SSID\"", "de:ad:be:ef:00:00")
        assertThat(h.wifi(MON.at(8, 0), network = rogue).outcomes.single())
            .isEqualTo(Outcome.Ignored(DecisionReason.BSSID_MISMATCH))
        assertThat(h.wifi(MON.at(8, 1), network = WifiNetwork(OFFICE_SSID, OFFICE_BSSID.uppercase())).arrival).isNotNull()
    }

    @Test
    fun `in-range SSID counts only when the option is on`() {
        assertThat(h.inRange(MON.at(8, 0), homeWifi, officeWifi).outcomes.single())
            .isEqualTo(Outcome.Ignored(DecisionReason.IN_RANGE_DISABLED))

        h.settings = h.settings.copy(wifiInRangeCountsAsPresent = true)
        val d = h.inRange(MON.at(8, 1), homeWifi, officeWifi)
        assertThat(d.arrival?.at).isEqualTo(MON.at(8, 1))
        assertThat(h.states[MON]?.wifiPresence?.inRangeOnly).isTrue()
    }
}
