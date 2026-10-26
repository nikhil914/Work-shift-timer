package com.arrivelog.domain.engine

import com.arrivelog.domain.engine.DecisionReason.ALREADY_ARRIVED
import com.arrivelog.domain.engine.DecisionReason.ARRIVAL_MOVED_EARLIER
import com.arrivelog.domain.engine.DecisionReason.ARRIVAL_RECORDED
import com.arrivelog.domain.engine.DecisionReason.EXIT_PENDING
import com.arrivelog.domain.engine.DecisionReason.EXIT_RECORDED
import com.arrivelog.domain.engine.DecisionReason.EXIT_REOPENED
import com.arrivelog.domain.testing.EngineHarness
import com.arrivelog.domain.testing.MON
import com.arrivelog.domain.testing.at
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The README's "Testing Phase 1 on the phone" steps 2–5, so the checklist cannot drift from the engine. */
class ShellWalkthroughTest {

    @Test
    fun `readme device checklist`() {
        val h = EngineHarness()
        fun reasons(d: Decision) = d.outcomes.map { it.reason }

        assertThat(reasons(h.wifi(MON.at(8, 7)))).containsExactly(ARRIVAL_RECORDED)
        assertThat(reasons(h.wifi(MON.at(8, 30)))).containsExactly(ALREADY_ARRIVED)
        assertThat(reasons(h.enter(MON.at(8, 3)))).containsExactly(ALREADY_ARRIVED)
        assertThat(reasons(h.dwell(MON.at(8, 5)))).containsExactly(ARRIVAL_MOVED_EARLIER)
        assertThat(h.day(MON)?.arrivalAt).isEqualTo(MON.at(8, 3))

        assertThat(reasons(h.exit(MON.at(16, 0)))).containsExactly(EXIT_PENDING)
        assertThat(reasons(h.settle(MON, MON.at(16, 30)))).containsExactly(EXIT_RECORDED)
        assertThat(reasons(h.wifi(MON.at(16, 20)))).containsExactly(EXIT_REOPENED)
        assertThat(h.day(MON)?.exitAt).isNull()
    }
}
