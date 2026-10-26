package com.arrivelog.domain.testing

import com.arrivelog.domain.model.AppSettings
import com.arrivelog.domain.model.DayStatus
import com.arrivelog.domain.model.DetectionState
import com.arrivelog.domain.model.RawEvent
import com.arrivelog.domain.model.SignalSource
import com.arrivelog.domain.model.WorkDay
import com.arrivelog.domain.model.Workplace
import com.arrivelog.domain.repository.DetectionStateRepository
import com.arrivelog.domain.repository.EventLogRepository
import com.arrivelog.domain.repository.SettingsRepository
import com.arrivelog.domain.repository.TransactionRunner
import com.arrivelog.domain.repository.WorkDayRepository
import com.arrivelog.domain.repository.WorkplaceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// In-memory repositories shared by the use-case tests.

class MutexTx : TransactionRunner {
    private val mutex = Mutex()
    override suspend fun <T> inTransaction(block: suspend () -> T): T = mutex.withLock { block() }
}

class FakeSettings(initial: AppSettings = AppSettings(setupCompleted = true)) : SettingsRepository {
    val state = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = state
    override suspend fun current(): AppSettings = state.value
    override suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings =
        transform(state.value).also { state.value = it }
}

class FakeWorkplaces(var all: List<Workplace>) : WorkplaceRepository {
    override fun observeAll(): Flow<List<Workplace>> = flowOf(all)
    override suspend fun getAll(): List<Workplace> = all
    override suspend fun upsert(workplace: Workplace): Long = workplace.id
    override suspend fun delete(id: Long) = Unit
}

/** Mirrors the SQL guards of WorkDayDao closely enough for use-case tests. */
class FakeWorkDays : WorkDayRepository {
    val rows = mutableMapOf<LocalDate, WorkDay>()
    var arrivalWrites = 0
    private val version = MutableStateFlow(0)

    private fun changed() {
        version.value++
    }

    override fun observe(date: LocalDate): Flow<WorkDay?> = version.map { rows[date] }
    override fun observeRange(from: LocalDate, to: LocalDate): Flow<List<WorkDay>> =
        version.map { rows.values.filter { it.date in from..to }.sortedByDescending { it.date } }

    override fun observeEarliestDate(): Flow<LocalDate?> = version.map { rows.keys.minOrNull() }

    override suspend fun get(date: LocalDate): WorkDay? {
        yield() // give the other coroutine a chance to interleave, as a real DB would
        return rows[date]
    }

    override suspend fun recordArrival(date: LocalDate, at: Instant, source: SignalSource, workplaceId: Long?, zone: ZoneId): Boolean {
        val row = rows[date] ?: WorkDay(date)
        val current = row.arrivalAt
        if (current != null && !(at.isBefore(current) && row.arrivalIsAutoAndUntouched)) return false
        rows[date] = row.copy(
            arrivalAt = at,
            arrivalSource = source,
            arrivalWorkplaceId = workplaceId,
            zoneId = zone,
            status = DayStatus.WORKING,
            originalArrivalAt = at,
        )
        arrivalWrites++
        changed()
        return true
    }

    override suspend fun recordExit(date: LocalDate, at: Instant, source: SignalSource): Boolean {
        val row = rows[date] ?: return false
        rows[date] = row.copy(exitAt = at, exitSource = source, originalExitAt = at)
        changed()
        return true
    }

    override suspend fun reopenExit(date: LocalDate, reentryAt: Instant): Boolean {
        val row = rows[date] ?: return false
        rows[date] = row.copy(exitAt = null, exitSource = null)
        changed()
        return true
    }

    override suspend fun markStatus(date: LocalDate, status: DayStatus): Boolean {
        val row = rows[date] ?: WorkDay(date)
        if (row.arrivalAt != null) return false
        rows[date] = row.copy(status = status)
        changed()
        return true
    }

    override suspend fun put(day: WorkDay) {
        rows[day.date] = day
        changed()
    }

    override suspend fun delete(date: LocalDate) {
        rows.remove(date)
        changed()
    }
}

class FakeStates : DetectionStateRepository {
    val rows = mutableMapOf<LocalDate, DetectionState>()
    override fun observe(date: LocalDate): Flow<DetectionState?> = flowOf(rows[date])
    override suspend fun get(date: LocalDate): DetectionState? = rows[date]
    override suspend fun put(state: DetectionState) {
        rows[state.date] = state
    }
    override suspend fun delete(date: LocalDate) {
        rows.remove(date)
    }
    override suspend fun deleteBefore(date: LocalDate) {
        rows.keys.removeAll { it < date }
    }
}

class FakeLog : EventLogRepository {
    val events = mutableListOf<RawEvent>()
    override fun observeRecent(limit: Int): Flow<List<RawEvent>> = flowOf(events.takeLast(limit))
    override suspend fun append(event: RawEvent): Long {
        events += event
        return events.size.toLong()
    }
    override suspend fun between(from: Instant, to: Instant): List<RawEvent> = events.filter { it.at in from..to }
    override suspend fun countReceivedBetween(from: Instant, to: Instant): Int = events.count { it.receivedAt in from..to }
    override suspend fun deleteOlderThan(cutoff: Instant): Int = 0
}
