package com.arrivelog.data.repository

import androidx.datastore.core.DataStore
import androidx.room.withTransaction
import com.arrivelog.data.db.ArriveLogDatabase
import com.arrivelog.data.db.DetectionStateDao
import com.arrivelog.data.db.RawEventDao
import com.arrivelog.data.db.WorkDayDao
import com.arrivelog.data.db.WorkplaceDao
import com.arrivelog.data.db.toDomain
import com.arrivelog.data.db.toEntity
import com.arrivelog.data.settings.SettingsDto
import com.arrivelog.data.settings.toDomain
import com.arrivelog.data.settings.toDto
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomWorkDayRepository @Inject constructor(
    private val dao: WorkDayDao,
    private val clock: Clock,
) : WorkDayRepository {
    override fun observe(date: LocalDate): Flow<WorkDay?> = dao.observe(date).map { it?.toDomain() }

    override fun observeRange(from: LocalDate, to: LocalDate): Flow<List<WorkDay>> =
        dao.observeRange(from, to).map { rows -> rows.map { it.toDomain() } }

    override fun observeEarliestDate(): Flow<LocalDate?> = dao.observeEarliestDate()

    override suspend fun get(date: LocalDate): WorkDay? = dao.get(date)?.toDomain()

    override suspend fun recordArrival(date: LocalDate, at: Instant, source: SignalSource, workplaceId: Long?, zone: ZoneId): Boolean =
        dao.recordArrival(date, at, source, workplaceId, zone.id, clock.instant())

    override suspend fun recordExit(date: LocalDate, at: Instant, source: SignalSource): Boolean =
        dao.recordExit(date, at, source, clock.instant()) > 0

    override suspend fun reopenExit(date: LocalDate, reentryAt: Instant): Boolean =
        dao.reopenExit(date, reentryAt, clock.instant()) > 0

    override suspend fun markStatus(date: LocalDate, status: DayStatus): Boolean =
        dao.markStatus(date, status, clock.instant())

    override suspend fun put(day: WorkDay) = dao.upsert(day.toEntity(clock.instant()))

    override suspend fun delete(date: LocalDate) = dao.delete(date)
}

@Singleton
class RoomDetectionStateRepository @Inject constructor(
    private val dao: DetectionStateDao,
) : DetectionStateRepository {
    override fun observe(date: LocalDate): Flow<DetectionState?> = dao.observe(date).map { it?.toDomain() }
    override suspend fun get(date: LocalDate): DetectionState? = dao.get(date)?.toDomain()
    override suspend fun put(state: DetectionState) = dao.upsert(state.toEntity())
    override suspend fun delete(date: LocalDate) = dao.delete(date)
    override suspend fun deleteBefore(date: LocalDate) = dao.deleteBefore(date)
}

@Singleton
class RoomEventLogRepository @Inject constructor(
    private val dao: RawEventDao,
) : EventLogRepository {
    override fun observeRecent(limit: Int): Flow<List<RawEvent>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    override suspend fun append(event: RawEvent): Long = dao.insert(event.toEntity())
    override suspend fun between(from: Instant, to: Instant): List<RawEvent> = dao.between(from, to).map { it.toDomain() }
    override suspend fun countReceivedBetween(from: Instant, to: Instant): Int = dao.countReceivedBetween(from, to)
    override suspend fun deleteOlderThan(cutoff: Instant): Int = dao.deleteOlderThan(cutoff)
}

@Singleton
class RoomWorkplaceRepository @Inject constructor(
    private val dao: WorkplaceDao,
) : WorkplaceRepository {
    override fun observeAll(): Flow<List<Workplace>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }
    override suspend fun getAll(): List<Workplace> = dao.getAll().map { it.toDomain() }
    override suspend fun upsert(workplace: Workplace): Long = dao.upsert(workplace.toEntity())
    override suspend fun delete(id: Long) = dao.delete(id)
}

@Singleton
class DataStoreSettingsRepository @Inject constructor(
    private val dataStore: DataStore<SettingsDto>,
) : SettingsRepository {
    override val settings: Flow<AppSettings> = dataStore.data.map { it.toDomain() }.distinctUntilChanged()
    override suspend fun current(): AppSettings = settings.first()
    override suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings =
        dataStore.updateData { transform(it.toDomain()).toDto() }.toDomain()
}

@Singleton
class RoomTransactionRunner @Inject constructor(
    private val db: ArriveLogDatabase,
) : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = db.withTransaction { block() }
}
