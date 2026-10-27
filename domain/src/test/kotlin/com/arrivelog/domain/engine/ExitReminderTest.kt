package com.arrivelog.domain.engine

import com.arrivelog.domain.model.DetectionSettings
import com.arrivelog.domain.model.DetectionState
import com.arrivelog.domain.model.DetectionTuning
import com.arrivelog.domain.model.PendingExit
import com.arrivelog.domain.model.ScheduleProfile
import com.arrivelog.domain.model.SignalSource
import com.arrivelog.domain.model.WorkDay
import com.arrivelog.domain.schedule.DayTimeline
import com.arrivelog.domain.testing.DUBAI
import com.arrivelog.domain.testing.MON
import com.arrivelog.domain.testing.at
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExitReminderTest {
    private val timeline = DayTimeline.of(MON, ScheduleProfile.DEFAULT_A, DUBAI, DetectionTuning())
    private val on = DetectionSettings(exitReminderEnabled = true)
    private val arrived = WorkDay(MON, arrivalAt = MON.at(8, 0), arrivalSource = SignalSource.WIFI)

    @Test
    fun `due at expected end plus two hours when still no exit`() {
        assertThat(ExitReminder.isDue(timeline, on, arrived, null, MON.at(18, 59))).isFalse()
        assertThat(ExitReminder.isDue(timeline, on, arrived, null, MON.at(19, 0))).isTrue()
    }

    @Test
    fun `never when off, without arrival, with an exit, or with an exit pending`() {
        assertThat(ExitReminder.isDue(timeline, DetectionSettings(), arrived, null, MON.at(19, 0))).isFalse()
        assertThat(ExitReminder.isDue(timeline, on, null, null, MON.at(19, 0))).isFalse()
        assertThat(ExitReminder.isDue(timeline, on, arrived.copy(exitAt = MON.at(17, 0)), null, MON.at(19, 0))).isFalse()
        val pending = DetectionState(MON, pendingExit = PendingExit(MON.at(18, 55), SignalSource.GEOFENCE))
        assertThat(ExitReminder.isDue(timeline, on, arrived, pending, MON.at(19, 0))).isFalse()
    }
}
