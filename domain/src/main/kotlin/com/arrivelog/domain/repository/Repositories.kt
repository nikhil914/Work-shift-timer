package com.arrivelog.domain.repository

import com.arrivelog.domain.model.AppSettings
import com.arrivelog.domain.model.DayStatus
import com.arrivelog.domain.model.DetectionState
import com.arrivelog.domain.model.RawEvent
import com.arrivelog.domain.model.SignalSource
import com.arrivelog.domain.model.WorkDay
import com.arrivelog.domain.model.Workplace
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

interface WorkDayRepository {
    fun observe(date: LocalDate): Flow<WorkDay?>
    fun observeRange(from: LocalDate, to: LocalDate): Flow<List<WorkDay>>
    suspend fun get(date: LocalDate): WorkDay?

    /**
     * Idempotent: creates the row if missing, then writes the arrival only if there is none yet, or
     * if [at] is earlier than an untouched auto arrival. Returns true if the stored arrival changed.
     */
    suspend fun recordArrival(date: LocalDate, at: Instant, source: SignalSource, workplaceId: Long?, zone: ZoneId): Boolean

    /** Last exit wins; never before arrival; never over a manual exit. Returns true if changed. */
    suspend fun recordExit(date: LocalDate, at: Instant, source: SignalSource): Boolean

    /** Clears an auto exit at or before [reentryAt]. Returns true if changed. */
    suspend fun reopenExit(date: LocalDate, reentryAt: Instant): Boolean

    /** Sets DAY_OFF / WFH / LEAVE for a day without an arrival. Returns true if changed. */
    suspend fun markStatus(date: LocalDate, status: DayStatus): Boolean

    suspend fun delete(date: LocalDate)
}

interface DetectionStateRepository {
    fun observe(date: LocalDate): Flow<DetectionState?>
    suspend fun get(date: LocalDate): DetectionState?
    suspend fun put(state: DetectionState)
    suspend fun delete(date: LocalDate)
    suspend fun deleteBefore(date: LocalDate)
}

interface EventLogRepository {
    fun observeRecent(limit: Int): Flow<List<RawEvent>>
    suspend fun append(event: RawEvent): Long
    suspend fun between(from: Instant, to: Instant): List<RawEvent>
    suspend fun countReceivedBetween(from: Instant, to: Instant): Int
    suspend fun deleteOlderThan(cutoff: Instant): Int
}

interface WorkplaceRepository {
    fun observeAll(): Flow<List<Workplace>>
    suspend fun getAll(): List<Workplace>
    suspend fun upsert(workplace: Workplace): Long
    suspend fun delete(id: Long)
}

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun current(): AppSettings
    suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings
}

/** Runs [block] atomically against the logs database (Room transaction in the app). */
interface TransactionRunner {
    suspend fun <T> inTransaction(block: suspend () -> T): T
}
