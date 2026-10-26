package com.arrivelog.domain.usecase

import com.arrivelog.domain.engine.ArrivalDecisionEngine
import com.arrivelog.domain.model.DayStatus
import com.arrivelog.domain.model.DetectionState
import com.arrivelog.domain.model.RawEvent
import com.arrivelog.domain.model.RawEventType
import com.arrivelog.domain.model.SignalSource
import com.arrivelog.domain.model.WorkDay
import com.arrivelog.domain.repository.DetectionStateRepository
import com.arrivelog.domain.repository.EventLogRepository
import com.arrivelog.domain.repository.TransactionRunner
import com.arrivelog.domain.repository.WorkDayRepository
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject

/** What the edit sheet submits. Times are wall-clock times on the day being edited. */
data class DayEdit(
    val arrival: LocalTime?,
    val exit: LocalTime?,
    val status: DayStatus,
    val note: String?,
)

enum class EditError {
    EXIT_WITHOUT_ARRIVAL,
    EXIT_NOT_AFTER_ARRIVAL,
    TIME_IN_FUTURE,

    /** HOLIDAY / WEEKEND come from the schedule and cannot be picked for a single day. */
    STATUS_NOT_SELECTABLE,
}

sealed interface EditResult {
    data class Saved(val day: WorkDay, val arrivalCleared: Boolean) : EditResult
    data object Unchanged : EditResult
    data class Invalid(val error: EditError) : EditResult
}

/**
 * Pure rules for a manual edit. The audit trail is never lost:
 *  - editing an auto-detected time keeps the detected time in originalArrivalAt / originalExitAt
 *    and sets the edited flag (shown as "edited", and later auto signals can no longer change it);
 *  - a time typed in where none was detected is stored with source MANUAL;
 *  - clearing an arrival also clears the exit, and lets detection log a fresh arrival.
 */
object DayEditor {
    val SELECTABLE_STATUSES = listOf(DayStatus.WORKING, DayStatus.DAY_OFF, DayStatus.WFH, DayStatus.LEAVE)

    fun apply(current: WorkDay?, date: LocalDate, edit: DayEdit, zone: ZoneId, now: Instant): EditResult {
        if (edit.status !in SELECTABLE_STATUSES && edit.status != current?.status) {
            return EditResult.Invalid(EditError.STATUS_NOT_SELECTABLE)
        }
        val base = current ?: WorkDay(date)
        val dayZone = base.zoneId ?: zone
        val arrivalAt = edit.arrival?.let { ZonedDateTime.of(date, it, dayZone).toInstant() }
        val exitAt = edit.exit?.let { ZonedDateTime.of(date, it, dayZone).toInstant() }

        if (exitAt != null && arrivalAt == null) return EditResult.Invalid(EditError.EXIT_WITHOUT_ARRIVAL)
        if (exitAt != null && arrivalAt != null && !exitAt.isAfter(arrivalAt)) return EditResult.Invalid(EditError.EXIT_NOT_AFTER_ARRIVAL)
        if (listOfNotNull(arrivalAt, exitAt).any { it.isAfter(now) }) return EditResult.Invalid(EditError.TIME_IN_FUTURE)

        var day = base.copy(
            status = edit.status,
            note = edit.note?.trim()?.takeIf { it.isNotEmpty() },
        )
        if (day.zoneId == null && arrivalAt != null) day = day.copy(zoneId = dayZone)

        // Same minute as stored = unchanged (the sheet only edits to the minute).
        val arrivalChanged = !sameMinute(base.arrivalAt, arrivalAt, dayZone)
        val exitChanged = !sameMinute(base.exitAt, exitAt, dayZone)
        val arrivalCleared = arrivalChanged && arrivalAt == null

        if (arrivalChanged) {
            day = when {
                arrivalAt == null -> day.copy(
                    arrivalAt = null,
                    arrivalSource = null,
                    arrivalWorkplaceId = null,
                    arrivalEdited = false,
                    originalArrivalAt = null,
                )
                base.arrivalAt == null -> day.copy(arrivalAt = arrivalAt, arrivalSource = SignalSource.MANUAL, arrivalEdited = false)
                else -> day.copy(
                    arrivalAt = arrivalAt,
                    arrivalEdited = base.arrivalSource != SignalSource.MANUAL,
                    originalArrivalAt = base.originalArrivalAt ?: base.arrivalAt,
                )
            }
        }
        if (exitChanged || arrivalCleared) {
            day = when {
                exitAt == null || arrivalCleared -> day.copy(exitAt = null, exitSource = null, exitEdited = false, originalExitAt = null)
                base.exitAt == null -> day.copy(exitAt = exitAt, exitSource = SignalSource.MANUAL, exitEdited = false)
                else -> day.copy(
                    exitAt = exitAt,
                    exitEdited = base.exitSource != SignalSource.MANUAL,
                    originalExitAt = base.originalExitAt ?: base.exitAt,
                )
            }
        }
        return if (day == base) EditResult.Unchanged else EditResult.Saved(day, arrivalCleared)
    }

    private fun sameMinute(a: Instant?, b: Instant?, zone: ZoneId): Boolean {
        if (a == null || b == null) return a == b
        val minute = { t: Instant -> t.atZone(zone).withSecond(0).withNano(0) }
        return minute(a) == minute(b)
    }
}

/** Saves the edit sheet. Runs in the same transaction discipline as background signals. */
class EditDayUseCase @Inject constructor(
    private val engine: ArrivalDecisionEngine,
    private val workDays: WorkDayRepository,
    private val states: DetectionStateRepository,
    private val eventLog: EventLogRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(date: LocalDate, edit: DayEdit): EditResult = tx.inTransaction {
        val now = engine.now()
        val result = DayEditor.apply(workDays.get(date), date, edit, engine.zone, now)
        if (result is EditResult.Saved) {
            workDays.put(result.day)
            if (result.arrivalCleared) forgetEvidence(states, date, now)
            eventLog.append(manualEvent(now, "EDIT:$date${if (result.arrivalCleared) ":ARRIVAL_CLEARED" else ""}"))
        }
        result
    }
}

/**
 * "Undo" on the arrival notification: the detected arrival was wrong (a drive-by, a neighbour's
 * Wi-Fi). Clears it and forgets the evidence behind it, so detection needs a fresh entry to log
 * again instead of re-logging the same presence a second later.
 */
class UndoArrivalUseCase @Inject constructor(
    private val engine: ArrivalDecisionEngine,
    private val workDays: WorkDayRepository,
    private val states: DetectionStateRepository,
    private val eventLog: EventLogRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(date: LocalDate): Boolean = tx.inTransaction {
        val day = workDays.get(date)
        if (day?.arrivalAt == null) return@inTransaction false
        val now = engine.now()
        workDays.put(
            day.copy(
                status = DayStatus.WORKING,
                arrivalAt = null,
                arrivalSource = null,
                arrivalWorkplaceId = null,
                arrivalEdited = false,
                originalArrivalAt = null,
                exitAt = null,
                exitSource = null,
                exitEdited = false,
                originalExitAt = null,
            ),
        )
        forgetEvidence(states, date, now)
        eventLog.append(manualEvent(now, "UNDO_ARRIVAL:$date"))
        true
    }
}

/** "Day off / WFH / Leave" for a day without an arrival. */
class MarkDayStatusUseCase @Inject constructor(
    private val engine: ArrivalDecisionEngine,
    private val workDays: WorkDayRepository,
    private val eventLog: EventLogRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(date: LocalDate, status: DayStatus): Boolean = tx.inTransaction {
        require(status in DayEditor.SELECTABLE_STATUSES) { "Not a selectable status: $status" }
        val changed = workDays.markStatus(date, status)
        if (changed) eventLog.append(manualEvent(engine.now(), "STATUS:$date:${status.name}"))
        changed
    }
}

private suspend fun forgetEvidence(states: DetectionStateRepository, date: LocalDate, now: Instant) {
    val previous = states.get(date)
    states.put(DetectionState(date, lastPresenceAt = previous?.lastPresenceAt, ignoreEvidenceBefore = now))
}

private fun manualEvent(now: Instant, reason: String) =
    RawEvent(at = now, receivedAt = now, type = RawEventType.MANUAL, accepted = true, reason = reason)
