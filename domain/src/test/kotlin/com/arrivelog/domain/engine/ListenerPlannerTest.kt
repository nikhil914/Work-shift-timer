package com.arrivelog.domain.engine

import com.arrivelog.domain.model.DetectionMode
import com.arrivelog.domain.testing.EngineHarness
import com.arrivelog.domain.testing.FRI
import com.arrivelog.domain.testing.MON
import com.arrivelog.domain.testing.SAT
import com.arrivelog.domain.testing.at
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Section 5b: what is allowed to be registered, and when. */
class ListenerPlannerTest {

    private val h = EngineHarness()

    @Test
    fun `weekend registers nothing`() {
        val plan = h.plan(SAT, SAT.at(8, 0))
        assertThat(plan.isIdle).isTrue()
        assertThat(plan.reason).isEqualTo(PlanReason.NON_WORKING_DAY)
    }

    @Test
    fun `nothing is armed before window start minus 15 minutes`() {
        val plan = h.plan(MON, MON.at(6, 14))
        assertThat(plan.isIdle).isTrue()
        assertThat(plan.nextChangeAt).isEqualTo(MON.at(6, 15))
    }

    @Test
    fun `arrival listeners are armed from 06_15 with default 180 s responsiveness`() {
        val plan = h.plan(MON, MON.at(6, 15))
        assertThat(plan.geofence).isEqualTo(GeofenceArming.ARRIVAL)
        assertThat(plan.wifiCallback).isTrue()
        assertThat(plan.responsivenessMs).isEqualTo(180_000)
        assertThat(plan.nextChangeAt).isEqualTo(MON.at(11, 10))
        assertThat(plan.initialTrigger).isTrue()
    }

    @Test
    fun `after Undo the fences are re-armed without the initial trigger`() {
        h.states[MON] = com.arrivelog.domain.model.DetectionState(MON, ignoreEvidenceBefore = MON.at(8, 30))
        val plan = h.plan(MON, MON.at(8, 31))
        assertThat(plan.geofence).isEqualTo(GeofenceArming.ARRIVAL)
        assertThat(plan.initialTrigger).isFalse()
        assertThat(GeofenceSignature.of(plan, h.workplaces, 120_000)).isNotEqualTo(
            GeofenceSignature.of(plan.copy(initialTrigger = true), h.workplaces, 120_000),
        )
    }

    @Test
    fun `faster detection uses 60 s responsiveness`() {
        h.settings = h.settings.copy(fasterDetection = true)
        assertThat(h.plan(MON, MON.at(8, 0)).responsivenessMs).isEqualTo(60_000)
    }

    @Test
    fun `wifi-only mode registers no geofence before arrival`() {
        h.mode(DetectionMode.WIFI_ONLY)
        val plan = h.plan(MON, MON.at(8, 0))
        assertThat(plan.geofence).isEqualTo(GeofenceArming.OFF)
        assertThat(plan.wifiCallback).isTrue()
    }

    @Test
    fun `wifi-only mode arms the geofence after arrival for exit detection`() {
        h.mode(DetectionMode.WIFI_ONLY)
        h.wifi(MON.at(8, 0))
        assertThat(h.plan(MON, MON.at(8, 1)).geofence).isEqualTo(GeofenceArming.PRESENCE)
    }

    @Test
    fun `no arrival and window closed means everything comes down`() {
        assertThat(h.plan(MON, MON.at(11, 10)).isIdle).isTrue()
    }

    @Test
    fun `after arrival with exit tracking off the rest of the day costs zero`() {
        h.settings = h.settings.copy(exitTrackingEnabled = false)
        h.wifi(MON.at(8, 0))
        val plan = h.plan(MON, MON.at(8, 1))
        assertThat(plan.isIdle).isTrue()
        assertThat(plan.reason).isEqualTo(PlanReason.ARRIVED_EXIT_TRACKING_OFF)
    }

    @Test
    fun `after arrival with exit tracking on only presence listeners stay until expected end plus 3 h`() {
        h.wifi(MON.at(8, 0))
        val plan = h.plan(MON, MON.at(8, 1))
        assertThat(plan.geofence).isEqualTo(GeofenceArming.PRESENCE)
        assertThat(plan.nextChangeAt).isEqualTo(MON.at(20, 0))
        assertThat(h.plan(MON, MON.at(20, 0)).isIdle).isTrue()
    }

    @Test
    fun `friday disarms at 15_00`() {
        h.wifi(FRI.at(7, 20))
        assertThat(h.plan(FRI, FRI.at(7, 21)).nextChangeAt).isEqualTo(FRI.at(15, 0))
    }

    @Test
    fun `no workplaces configured means nothing to arm`() {
        h.workplaces = emptyList()
        assertThat(h.plan(MON, MON.at(8, 0)).reason).isEqualTo(PlanReason.NO_WORKPLACES)
    }
}
