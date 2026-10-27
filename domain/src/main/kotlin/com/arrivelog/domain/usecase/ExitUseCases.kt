package com.arrivelog.domain.usecase

import com.arrivelog.domain.engine.ArrivalDecisionEngine
import com.arrivelog.domain.model.RawEvent
import com.arrivelog.domain.model.RawEventType
import com.arrivelog.domain.model.SignalSource
import com.arrivelog.domain.repository.EventLogRepository
import com.arrivelog.domain.repository.TransactionRunner
import com.arrivelog.domain.repository.WorkDayRepository
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

/**
 * "Left now" on the exit reminder. Only fills an empty exit after the arrival; a manual exit is
 * final for the day (auto exits never overwrite it, same as an exit typed in the edit sheet).
 */
class LogExitUseCase @Inject constructor(
    private val engine: ArrivalDecisionEngine,
    private val workDays: WorkDayRepository,
    private val eventLog: EventLogRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(date: LocalDate, at: Instant = engine.now()): Boolean = tx.inTransaction {
        val day = workDays.get(date) ?: return@inTransaction false
        val arrival = day.arrivalAt ?: return@inTransaction false
        if (day.exitAt != null || !at.isAfter(arrival)) return@inTransaction false
        workDays.save(day.copy(exitAt = at, exitSource = SignalSource.MANUAL, exitEdited = true))
        val now = engine.now()
        eventLog.append(RawEvent(at = at, receivedAt = now, type = RawEventType.MANUAL, accepted = true, reason = "EXIT_MANUAL"))
        true
    }
}
