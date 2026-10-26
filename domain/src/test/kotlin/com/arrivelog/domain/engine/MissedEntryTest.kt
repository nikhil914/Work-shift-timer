package com.arrivelog.domain.engine

import com.arrivelog.domain.model.DayStatus
import com.arrivelog.domain.model.WorkDay
import com.arrivelog.domain.testing.EngineHarness
import com.arrivelog.domain.testing.FRI
import com.arrivelog.domain.testing.MON
import com.arrivelog.domain.testing.SAT
import com.arrivelog.domain.testing.at
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MissedEntryTest {

    private val h = EngineHarness()

    @Test
    fun `no arrival by the end of the window notifies`() {
        // Window ends 11:00; the check runs at 11:00 + 10 min grace.
        assertThat(h.missedEntry(MON, MON.at(11, 10))).isEqualTo(MissedEntryDecision.Notify(null))
    }

    @Test
    fun `job running early asks to be rescheduled`() {
        assertThat(h.missedEntry(MON, MON.at(10, 50))).isEqualTo(MissedEntryDecision.NotYet(MON.at(11, 10)))
    }

    @Test
    fun `friday check follows profile B`() {
        assertThat(h.missedEntry(FRI, FRI.at(10, 5))).isInstanceOf(MissedEntryDecision.NotYet::class.java)
        assertThat(h.missedEntry(FRI, FRI.at(10, 10))).isEqualTo(MissedEntryDecision.Notify(null))
    }

    @Test
    fun `arrival logged means no reminder`() {
        h.wifi(MON.at(8, 0))
        assertThat(h.missedEntry(MON, MON.at(11, 10))).isEqualTo(MissedEntryDecision.NotNeeded(DecisionReason.ALREADY_ARRIVED))
    }

    @Test
    fun `weekend never reminds`() {
        assertThat(h.missedEntry(SAT, SAT.at(11, 10))).isEqualTo(MissedEntryDecision.NotNeeded(DecisionReason.NON_WORKING_DAY))
    }

    @Test
    fun `explicit status means no reminder`() {
        h.days[MON] = WorkDay(MON, status = DayStatus.LEAVE)
        assertThat(h.missedEntry(MON, MON.at(11, 10)))
            .isEqualTo(MissedEntryDecision.NotNeeded(DecisionReason.DAY_MARKED_NOT_WORKING))
    }

    @Test
    fun `presence that could not be auto-logged is offered as a suggestion`() {
        // Presence seen before detection was armed (e.g. app opened at 06:05) is never auto-logged.
        h.arriveByGeofence(MON.at(6, 5))
        assertThat(h.day(MON)).isNull()
        assertThat(h.missedEntry(MON, MON.at(11, 10))).isEqualTo(MissedEntryDecision.Notify(MON.at(6, 5)))
    }

    @Test
    fun `re-notify check returns the same verdict while still unresolved`() {
        assertThat(h.missedEntry(MON, MON.at(12, 10))).isEqualTo(MissedEntryDecision.Notify(null))
        assertThat(h.engine.timelineFor(MON, h.schedule)?.missedEntryRenotifyAt).isEqualTo(MON.at(12, 10))
    }
}
