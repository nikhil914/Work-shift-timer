package com.arrivelog.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.arrivelog.domain.model.DayStatus
import com.arrivelog.domain.model.SignalSource
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

@Dao
abstract class WorkDayDao {

    @Query("SELECT * FROM work_day WHERE date = :date")
    abstract suspend fun get(date: LocalDate): WorkDayEntity?

    @Query("SELECT * FROM work_day WHERE date = :date")
    abstract fun observe(date: LocalDate): Flow<WorkDayEntity?>

    @Query("SELECT * FROM work_day WHERE date BETWEEN :from AND :to ORDER BY date DESC")
    abstract fun observeRange(from: LocalDate, to: LocalDate): Flow<List<WorkDayEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIfAbsent(entity: WorkDayEntity): Long

    /**
     * The SQL itself enforces the rules, independently of the engine, so a bug upstream or two
     * concurrent writers can never break them:
     *  - no arrival yet -> write it;
     *  - an arrival exists -> only an *earlier* time may replace it, and only if it is an untouched
     *    auto arrival (not edited, not manual).
     */
    @Query(
        """
        UPDATE work_day SET
            arrivalAt = :at, arrivalSource = :source, arrivalWorkplaceId = :workplaceId,
            zoneId = :zoneId, originalArrivalAt = :at, status = 'WORKING', updatedAt = :now
        WHERE date = :date AND (
            arrivalAt IS NULL
            OR (arrivalAt > :at AND arrivalEdited = 0 AND arrivalSource != 'MANUAL')
        )
        """,
    )
    protected abstract suspend fun updateArrival(
        date: LocalDate,
        at: Instant,
        source: SignalSource,
        workplaceId: Long?,
        zoneId: String,
        now: Instant,
    ): Int

    @Transaction
    open suspend fun recordArrival(
        date: LocalDate,
        at: Instant,
        source: SignalSource,
        workplaceId: Long?,
        zoneId: String,
        now: Instant,
    ): Boolean {
        insertIfAbsent(blank(date, now))
        return updateArrival(date, at, source, workplaceId, zoneId, now) > 0
    }

    /** Last exit wins, never before arrival, never over a manually set exit. */
    @Query(
        """
        UPDATE work_day SET exitAt = :at, exitSource = :source, originalExitAt = :at, updatedAt = :now
        WHERE date = :date
            AND arrivalAt IS NOT NULL AND arrivalAt < :at
            AND exitEdited = 0 AND (exitSource IS NULL OR exitSource != 'MANUAL')
            AND (exitAt IS NULL OR exitAt < :at)
        """,
    )
    abstract suspend fun recordExit(date: LocalDate, at: Instant, source: SignalSource, now: Instant): Int

    @Query(
        """
        UPDATE work_day SET exitAt = NULL, exitSource = NULL, originalExitAt = NULL, updatedAt = :now
        WHERE date = :date AND exitAt IS NOT NULL AND exitAt <= :reentryAt
            AND exitEdited = 0 AND exitSource != 'MANUAL'
        """,
    )
    abstract suspend fun reopenExit(date: LocalDate, reentryAt: Instant, now: Instant): Int

    @Query("UPDATE work_day SET status = :status, updatedAt = :now WHERE date = :date AND arrivalAt IS NULL")
    protected abstract suspend fun updateStatus(date: LocalDate, status: DayStatus, now: Instant): Int

    @Transaction
    open suspend fun markStatus(date: LocalDate, status: DayStatus, now: Instant): Boolean {
        insertIfAbsent(blank(date, now))
        return updateStatus(date, status, now) > 0
    }

    @Query("DELETE FROM work_day WHERE date = :date")
    abstract suspend fun delete(date: LocalDate)

    private fun blank(date: LocalDate, now: Instant) = WorkDayEntity(
        date = date,
        status = DayStatus.WORKING,
        arrivalAt = null,
        arrivalSource = null,
        arrivalWorkplaceId = null,
        exitAt = null,
        exitSource = null,
        zoneId = null,
        note = null,
        arrivalEdited = false,
        exitEdited = false,
        originalArrivalAt = null,
        originalExitAt = null,
        updatedAt = now,
    )
}

@Dao
interface RawEventDao {
    @Insert
    suspend fun insert(event: RawEventEntity): Long

    @Query("SELECT * FROM event_log ORDER BY receivedAt DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<RawEventEntity>>

    @Query("SELECT * FROM event_log WHERE at BETWEEN :from AND :to ORDER BY at ASC, id ASC")
    suspend fun between(from: Instant, to: Instant): List<RawEventEntity>

    @Query("SELECT COUNT(*) FROM event_log WHERE receivedAt BETWEEN :from AND :to")
    suspend fun countReceivedBetween(from: Instant, to: Instant): Int

    @Query("DELETE FROM event_log WHERE receivedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Instant): Int
}

@Dao
interface WorkplaceDao {
    @Query("SELECT * FROM workplace ORDER BY id ASC")
    fun observeAll(): Flow<List<WorkplaceEntity>>

    @Query("SELECT * FROM workplace ORDER BY id ASC")
    suspend fun getAll(): List<WorkplaceEntity>

    @Upsert
    suspend fun upsert(workplace: WorkplaceEntity): Long

    @Query("DELETE FROM workplace WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface DetectionStateDao {
    @Query("SELECT * FROM detection_state WHERE date = :date")
    suspend fun get(date: LocalDate): DetectionStateEntity?

    @Query("SELECT * FROM detection_state WHERE date = :date")
    fun observe(date: LocalDate): Flow<DetectionStateEntity?>

    @Upsert
    suspend fun upsert(state: DetectionStateEntity)

    @Query("DELETE FROM detection_state WHERE date = :date")
    suspend fun delete(date: LocalDate)

    @Query("DELETE FROM detection_state WHERE date < :date")
    suspend fun deleteBefore(date: LocalDate)
}
