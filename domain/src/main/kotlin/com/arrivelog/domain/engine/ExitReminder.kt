package com.arrivelog.domain.engine

import com.arrivelog.domain.model.DetectionSettings
import com.arrivelog.domain.model.DetectionState
import com.arrivelog.domain.model.WorkDay
import com.arrivelog.domain.schedule.DayTimeline
import java.time.Instant

/** "Still at work? Add exit time" at expected end + 2 h (spec section 3, opt-in, off by default). */
object ExitReminder {
    fun isDue(timeline: DayTimeline, settings: DetectionSettings, day: WorkDay?, state: DetectionState?, now: Instant): Boolean =
        settings.exitReminderEnabled &&
            day?.arrivalAt != null &&
            day.exitAt == null &&
            // An exit already waiting out its debounce will be committed; don't nag about it.
            state?.pendingExit == null &&
            !now.isBefore(timeline.exitReminderAt)
}
