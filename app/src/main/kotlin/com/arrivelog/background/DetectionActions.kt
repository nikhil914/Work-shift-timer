package com.arrivelog.background

import java.time.LocalDate

/**
 * What the UI may ask of the background layer. An interface so the Home ViewModel can be unit
 * tested without the coordinator (and its Play services / AlarmManager dependencies).
 */
interface DetectionActions {
    /** Manual arrival now; true if it was logged (false if the day already had one). */
    suspend fun logNow(): Boolean

    /** Re-plan listeners and notifications after the user changed a day. */
    suspend fun afterUserEdit(date: LocalDate)
}
