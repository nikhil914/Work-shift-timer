package com.arrivelog.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        WorkDayEntity::class,
        RawEventEntity::class,
        WorkplaceEntity::class,
        DetectionStateEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class ArriveLogDatabase : RoomDatabase() {
    abstract fun workDayDao(): WorkDayDao
    abstract fun rawEventDao(): RawEventDao
    abstract fun workplaceDao(): WorkplaceDao
    abstract fun detectionStateDao(): DetectionStateDao

    companion object {
        const val NAME = "arrivelog.db"
    }
}
