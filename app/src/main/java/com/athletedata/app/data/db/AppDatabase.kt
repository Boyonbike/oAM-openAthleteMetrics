package com.athletedata.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The single Room database for the app.
 *
 * All four tables are combined here. [Converters] is registered at this level
 * so TypeConverter logic applies to every entity, every @Query parameter, and
 * every result column across all DAOs automatically.
 *
 * Schema version history:
 *   1 — initial schema: metric_readings, sleep_sessions, daily_summary, daily_context
 *
 * To enable schema export for migration tracking, set exportSchema = true and
 * add `ksp { arg("room.schemaLocation", "$projectDir/schemas") }` to
 * app/build.gradle.kts before the first schema migration.
 */
@Database(
    entities = [
        MetricReadingEntity::class,
        SleepSessionEntity::class,
        DailySummaryEntity::class,
        DailyContextEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun metricReadingDao(): MetricReadingDao
    abstract fun sleepSessionDao(): SleepSessionDao
    abstract fun dailySummaryDao(): DailySummaryDao
    abstract fun dailyContextDao(): DailyContextDao

    companion object {
        const val DATABASE_NAME = "athlete_data.db"
    }
}
