package com.arrivelog.ui.home

import android.content.Intent
import java.time.LocalDate
import java.time.LocalTime

enum class EditFocus {
    NONE,

    /** Open the arrival time picker straight away ("Edit time", "Pick time"). */
    ARRIVAL,

    /** Jump to the Day off / WFH / Leave choice. */
    STATUS,
}

/** "Open the edit sheet for this day", carried from a notification into MainActivity. */
data class EditRequest(
    val date: LocalDate,
    val focus: EditFocus,
    /** Preselected in the arrival picker, e.g. presence seen before the window opened. */
    val suggestedArrival: LocalTime? = null,
) {
    fun writeTo(intent: Intent) {
        intent.putExtra(EXTRA_DATE, date.toString())
        intent.putExtra(EXTRA_FOCUS, focus.name)
        suggestedArrival?.let { intent.putExtra(EXTRA_SUGGESTED, it.toString()) }
    }

    companion object {
        private const val EXTRA_DATE = "com.arrivelog.extra.EDIT_DATE"
        private const val EXTRA_FOCUS = "com.arrivelog.extra.EDIT_FOCUS"
        private const val EXTRA_SUGGESTED = "com.arrivelog.extra.SUGGESTED_ARRIVAL"

        fun from(intent: Intent?): EditRequest? {
            val date = intent?.getStringExtra(EXTRA_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
            val focus = intent.getStringExtra(EXTRA_FOCUS)?.let { runCatching { EditFocus.valueOf(it) }.getOrNull() } ?: EditFocus.NONE
            val suggested = intent.getStringExtra(EXTRA_SUGGESTED)?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
            return EditRequest(date, focus, suggested)
        }
    }
}
