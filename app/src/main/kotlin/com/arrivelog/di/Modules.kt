package com.arrivelog.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import androidx.room.Room
import com.arrivelog.background.DetectionActions
import com.arrivelog.background.DetectionCoordinator
import com.arrivelog.data.db.ArriveLogDatabase
import com.arrivelog.data.db.DetectionStateDao
import com.arrivelog.data.db.RawEventDao
import com.arrivelog.data.db.WorkDayDao
import com.arrivelog.data.db.WorkplaceDao
import com.arrivelog.data.repository.DataStoreSettingsRepository
import com.arrivelog.data.repository.RoomDetectionStateRepository
import com.arrivelog.data.repository.RoomEventLogRepository
import com.arrivelog.data.repository.RoomTransactionRunner
import com.arrivelog.data.repository.RoomWorkDayRepository
import com.arrivelog.data.repository.RoomWorkplaceRepository
import com.arrivelog.data.settings.SettingsDto
import com.arrivelog.data.settings.SettingsSerializer
import com.arrivelog.domain.model.DetectionTuning
import com.arrivelog.domain.repository.DetectionStateRepository
import com.arrivelog.domain.repository.EventLogRepository
import com.arrivelog.domain.repository.SettingsRepository
import com.arrivelog.domain.repository.TransactionRunner
import com.arrivelog.domain.repository.WorkDayRepository
import com.arrivelog.domain.repository.WorkplaceRepository
import com.arrivelog.domain.time.DeviceZoneClock
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.Clock
import javax.inject.Qualifier
import javax.inject.Singleton

/** Process-wide scope for work that must outlive a screen (replaces any temptation of GlobalScope). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun clock(): Clock = DeviceZoneClock()

    @Provides
    @Singleton
    fun tuning(): DetectionTuning = DetectionTuning()
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): ArriveLogDatabase =
        Room.databaseBuilder(context, ArriveLogDatabase::class.java, ArriveLogDatabase.NAME)
            // No destructive fallback: losing the attendance log silently is never acceptable.
            // Schema changes ship with explicit migrations (schemas/ is exported for that).
            .build()

    @Provides fun workDayDao(db: ArriveLogDatabase): WorkDayDao = db.workDayDao()
    @Provides fun rawEventDao(db: ArriveLogDatabase): RawEventDao = db.rawEventDao()
    @Provides fun workplaceDao(db: ArriveLogDatabase): WorkplaceDao = db.workplaceDao()
    @Provides fun detectionStateDao(db: ArriveLogDatabase): DetectionStateDao = db.detectionStateDao()
}

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {
    @Provides
    @Singleton
    fun settingsDataStore(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
    ): DataStore<SettingsDto> = DataStoreFactory.create(
        serializer = SettingsSerializer,
        // A corrupt file falls back to defaults (setup wizard runs again) instead of crashing every
        // background receiver. The attendance log itself lives in Room and is unaffected.
        corruptionHandler = ReplaceFileCorruptionHandler { SettingsSerializer.defaultValue },
        scope = CoroutineScope(scope.coroutineContext + Dispatchers.IO),
        produceFile = { context.dataStoreFile(SETTINGS_FILE) },
    )

    private const val SETTINGS_FILE = "settings.json"
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds abstract fun workDays(impl: RoomWorkDayRepository): WorkDayRepository
    @Binds abstract fun states(impl: RoomDetectionStateRepository): DetectionStateRepository
    @Binds abstract fun eventLog(impl: RoomEventLogRepository): EventLogRepository
    @Binds abstract fun workplaces(impl: RoomWorkplaceRepository): WorkplaceRepository
    @Binds abstract fun settings(impl: DataStoreSettingsRepository): SettingsRepository
    @Binds abstract fun transactions(impl: RoomTransactionRunner): TransactionRunner
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BackgroundModule {
    @Binds abstract fun detectionActions(impl: DetectionCoordinator): DetectionActions
}
