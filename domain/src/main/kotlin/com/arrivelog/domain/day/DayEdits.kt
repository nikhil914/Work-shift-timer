package com.arrivelog.domain.day

import com.arrivelog.domain.model.DayStatus
import com.arrivelog.domain.model.SignalSource
import com.arrivelog.domain.model.WorkDay
import java.time.Instant
import java.time.ZoneId

/** What the edit sheet submits. Null arrival/exit means "none". */
data class DayEdit(
    val arrivalAt: Instant?,
    val exitAt: Instant?,
    val status: DayStatus,
    val note: String?,
)

enum class EditProblem { EXIT_WITHOUT_ARRIVAL, EXIT_BEFORE_ARRIVAL, TIMES_ON_NON_WORKING_DAY }

/**
 * Pure merge of a manual edit into the stored day, keeping the audit trail: the original
 * auto-detected times stay in originalArrivalAt / originalExitAt, and edited flags are set only
 * for the fields that actually changed.
 */
object DayEditor {
    fun validate(edit: DayEdit): Set<EditProblem> = buildSet {
        val a = edit.arrivalAt
        val e = edit.exitAt
        if (e != null && a == null) add(EditProblem.EXIT_WITHOUT_ARRIVAL)
        if (e != null && a != null && !e.isAfter(a)) add(EditProblem.EXIT_BEFORE_ARRIVAL)
        if (edit.status != DayStatus.WORKING && (a != null || e != null)) add(EditProblem.TIMES_ON_NON_WORKING_DAY)
    }

    fun apply(current: WorkDay, edit: DayEdit, zone: ZoneId): WorkDay {
        val arrivalChanged = edit.arrivalAt != current.arrivalAt
        val exitChanged = edit.exitAt != current.exitAt
        return current.copy(
            status = edit.status,
            arrivalAt = edit.arrivalAt,
            arrivalSource = when {
                edit.arrivalAt == null -> null
                current.arrivalAt == null -> SignalSource.MANUAL
                else -> current.arrivalSource
            },
            arrivalWorkplaceId = if (edit.arrivalAt == null) null else current.arrivalWorkplaceId,
            exitAt = edit.exitAt,
            exitSource = when {
                edit.exitAt == null -> null
                exitChanged -> SignalSource.MANUAL
                else -> current.exitSource
            },
            zoneId = current.zoneId ?: zone,
            note = edit.note?.trim()?.takeIf { it.isNotEmpty() },
            arrivalEdited = if (arrivalChanged) edit.arrivalAt != null else current.arrivalEdited,
            exitEdited = if (exitChanged) edit.exitAt != null else current.exitEdited,
            // Audit: keep the first auto value; never overwrite it with a manual one.
            originalArrivalAt = current.originalArrivalAt,
            originalExitAt = current.originalExitAt,
        )
    }

    /** Undo of an auto arrival: back to "no arrival yet", audit kept. */
    fun undoArrival(current: WorkDay): WorkDay = current.copy(
        arrivalAt = null,
        arrivalSource = null,
        arrivalWorkplaceId = null,
        exitAt = null,
        exitSource = null,
        arrivalEdited = false,
        exitEdited = false,
    )
}
