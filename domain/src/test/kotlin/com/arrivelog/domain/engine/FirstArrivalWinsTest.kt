package com.arrivelog.domain.engine

import com.arrivelog.domain.model.DayStatus
import com.arrivelog.domain.model.DetectionState
import com.arrivelog.domain.model.SignalSource
import com.arrivelog.domain.model.WorkDay
import com.arrivelog.domain.testing.EngineHarness
import com.arrivelog.domain.testing.MON
import com.arrivelog.domain.testing.SAT
import com.arrivelog.domain.testing.at
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FirstArrivalWinsTest {

    private val h = EngineHarness()

    @Test
    fun `a later signal never overwrites the logged arrival`() {
        h.wifi(MON.at(8, 7))
        val later = h.arriveByGeofence(MON.at(9, 30))
        assertThat(later.outcomes.last()).isEqualTo(Outcome.Ignored(DecisionReason.ALREADY_ARRIVED))
        assertThat(h.day(MON)?.arrivalAt).isEqualTo(MON.at(8, 7))
    }

    @Test
    fun `replaying the same signal is a no-op`() {
        h.wifi(MON.at(8, 7))
        val replay = h.wifi(MON.at(8, 7))
        assertThat(replay.arrival).isNull()
        assertThat(h.day(MON)?.arrivalAt).isEqualTo(MON.at(8, 7))
    }

    @Test
    fun `late-delivered geofence proving an earlier arrival moves an untouched auto arrival earlier`() {
        // Wi-Fi processed first at 08:07; the 08:03 ENTER/DWELL is delivered minutes later.
        h.wifi(MON.at(8, 7))
        h.enter(MON.at(8, 3), receivedAt = MON.at(8, 9))
        val d = h.dwell(MON.at(8, 5), receivedAt = MON.at(8, 10))
        assertThat(d.arrival?.replacesLaterRecord).isTrue()
        assertThat(d.arrival?.reason).isEqualTo(DecisionReason.ARRIVAL_MOVED_EARLIER)
        assertThat(h.day(MON)?.arrivalAt).isEqualTo(MON.at(8, 3))
    }

    @Test
    fun `a manually edited arrival is never moved by detection`() {
        h.days[MON] = WorkDay(MON, arrivalAt = MON.at(8, 30), arrivalSource = SignalSource.WIFI, arrivalEdited = true)
        h.arriveByGeofence(MON.at(8, 0))
        assertThat(h.day(MON)?.arrivalAt).isEqualTo(MON.at(8, 30))
    }

    @Test
    fun `manual log now works outside the window and is not overwritten`() {
        val manual = h.manual(MON.at(11, 45))
        assertThat(manual.arrival?.source).isEqualTo(SignalSource.MANUAL)
        assertThat(manual.arrival?.reason).isEqualTo(DecisionReason.MANUAL_ARRIVAL)
        h.arriveByGeofence(MON.at(9, 0)) // even an earlier late-delivered auto signal
        assertThat(h.day(MON)?.arrivalAt).isEqualTo(MON.at(11, 45))
    }

    @Test
    fun `manual log on a weekend turns the day into a working day`() {
        val d = h.manual(SAT.at(10, 0))
        assertThat(d.arrival).isNotNull()
        assertThat(h.day(SAT)?.status).isEqualTo(DayStatus.WORKING)
    }

    @Test
    fun `manual log is ignored when an arrival already exists`() {
        h.wifi(MON.at(8, 7))
        assertThat(h.manual(MON.at(8, 40)).outcomes.single()).isEqualTo(Outcome.Ignored(DecisionReason.ALREADY_ARRIVED))
    }

    @Test
    fun `after undo, evidence from before the undo is not reused`() {
        h.states[MON] = DetectionState(MON, ignoreEvidenceBefore = MON.at(8, 30))
        val stale = h.wifi(MON.at(8, 10), receivedAt = MON.at(8, 31))
        assertThat(stale.outcomes.single()).isEqualTo(Outcome.Ignored(DecisionReason.EVIDENCE_UNDONE))
        assertThat(h.wifi(MON.at(8, 40)).arrival?.at).isEqualTo(MON.at(8, 40))
    }
}
