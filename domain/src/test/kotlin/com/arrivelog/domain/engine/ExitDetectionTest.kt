package com.arrivelog.domain.engine

import com.arrivelog.domain.model.SignalSource
import com.arrivelog.domain.testing.EngineHarness
import com.arrivelog.domain.testing.MON
import com.arrivelog.domain.testing.at
import com.arrivelog.domain.testing.officeWifi
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

class ExitDetectionTest {

    private val h = EngineHarness()

    @Before
    fun arrive() {
        h.wifi(MON.at(8, 0))
    }

    @Test
    fun `exit starts a debounce and writes nothing yet`() {
        val d = h.exit(MON.at(12, 30))
        assertThat(d.outcomes.single()).isEqualTo(Outcome.Noted(DecisionReason.EXIT_PENDING))
        assertThat(h.day(MON)?.exitAt).isNull()
        assertThat(h.states[MON]?.pendingExit?.at).isEqualTo(MON.at(12, 30))
    }

    @Test
    fun `re-entry within 10 minutes cancels the exit`() {
        h.exit(MON.at(12, 30))
        val back = h.wifi(MON.at(12, 37))
        assertThat(back.outcomes.single()).isEqualTo(Outcome.Noted(DecisionReason.EXIT_CANCELLED_REENTRY))
        h.settle(MON, MON.at(18, 0))
        assertThat(h.day(MON)?.exitAt).isNull()
    }

    @Test
    fun `geofence re-ENTER also cancels the exit`() {
        h.exit(MON.at(12, 30))
        h.enter(MON.at(12, 34))
        assertThat(h.states[MON]?.pendingExit).isNull()
    }

    @Test
    fun `settling before the debounce elapses does nothing`() {
        h.exit(MON.at(17, 0))
        h.settle(MON, MON.at(17, 9))
        assertThat(h.day(MON)?.exitAt).isNull()
        assertThat(h.states[MON]?.pendingExit).isNotNull()
    }

    @Test
    fun `settling after the debounce records the true exit time`() {
        h.exit(MON.at(17, 2))
        val d = h.settle(MON, MON.at(20, 0))
        assertThat(d.exit).isEqualTo(Outcome.ExitRecorded(MON.at(17, 2), SignalSource.GEOFENCE))
        assertThat(h.day(MON)?.exitAt).isEqualTo(MON.at(17, 2))
    }

    @Test
    fun `last exit of the day wins`() {
        h.exit(MON.at(12, 30))
        h.settle(MON, MON.at(12, 45)) // lunch exit committed
        h.wifi(MON.at(13, 15)) // back: day reopened
        h.exit(MON.at(17, 5))
        h.settle(MON, MON.at(20, 0))
        assertThat(h.day(MON)?.exitAt).isEqualTo(MON.at(17, 5))
    }

    @Test
    fun `coming back after a committed exit reopens the day`() {
        h.exit(MON.at(12, 30))
        h.settle(MON, MON.at(12, 45))
        assertThat(h.day(MON)?.exitAt).isEqualTo(MON.at(12, 30))
        val back = h.wifi(MON.at(13, 15))
        assertThat(back.outcomes.single()).isEqualTo(Outcome.ExitReopened(MON.at(13, 15)))
        assertThat(h.day(MON)?.exitAt).isNull()
    }

    @Test
    fun `a later signal settles a due exit before handling itself`() {
        h.exit(MON.at(12, 30))
        // Next signal 45 minutes later: the exit is committed first (event time), then the day reopens.
        val back = h.enter(MON.at(13, 15))
        assertThat(back.outcomes.map { it.reason })
            .containsExactly(DecisionReason.EXIT_RECORDED, DecisionReason.EXIT_REOPENED).inOrder()
    }

    @Test
    fun `exit and wifi-lost for one departure keep the earlier time`() {
        h.wifiLost(MON.at(17, 0))
        h.exit(MON.at(17, 3))
        h.settle(MON, MON.at(18, 0))
        assertThat(h.day(MON)?.exitAt).isEqualTo(MON.at(17, 0))
        assertThat(h.day(MON)?.exitSource).isEqualTo(SignalSource.WIFI)
    }

    @Test
    fun `exit tracking off ignores exits`() {
        h.settings = h.settings.copy(exitTrackingEnabled = false)
        assertThat(h.exit(MON.at(17, 0)).outcomes.single()).isEqualTo(Outcome.Ignored(DecisionReason.EXIT_TRACKING_OFF))
    }

    @Test
    fun `exit before any arrival is ignored`() {
        val other = EngineHarness()
        val d = other.exit(MON.at(7, 0))
        assertThat(d.outcomes.single()).isEqualTo(Outcome.Ignored(DecisionReason.EXIT_BEFORE_ARRIVAL))
    }

    @Test
    fun `out-of-order EXIT older than a processed re-entry is stale`() {
        // Re-entry on Wi-Fi at 12:35 is processed before the 12:30 EXIT is delivered.
        h.wifi(MON.at(12, 35))
        val d = h.exit(MON.at(12, 30), receivedAt = MON.at(12, 38))
        assertThat(d.outcomes.single()).isEqualTo(Outcome.Ignored(DecisionReason.STALE_EXIT))
        assertThat(h.states[MON]?.pendingExit).isNull()
    }

    @Test
    fun `geofence EXIT while still on office wifi is GPS jitter`() {
        val d = h.exit(MON.at(14, 0), connected = officeWifi)
        assertThat(d.outcomes.single()).isEqualTo(Outcome.Ignored(DecisionReason.EXIT_CONTRADICTED_BY_WIFI))
    }

    @Test
    fun `wifi lost for an unrelated network is ignored`() {
        val d = h.wifiLost(MON.at(17, 0), network = com.arrivelog.domain.testing.homeWifi)
        assertThat(d.outcomes.single()).isEqualTo(Outcome.Ignored(DecisionReason.SSID_NOT_OFFICE))
    }
}
