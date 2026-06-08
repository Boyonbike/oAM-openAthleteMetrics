package com.athletedata.openAthleteMetrics.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema version history:
 *   1 — initial schema: metric_readings, sleep_sessions, daily_summary, daily_context
 *   2 — (prior migration)
 *   3 — added question_definitions, question_responses
 *   4 — added is_starred to question_definitions; first 5 lifestyle questions starred by default
 *   5 — unique index on metric_readings(driver_id, metric_type, recorded_at) for BLE deduplication
 *   6 — added activities, devices, sync_sessions, raw_device_data tables for BLE device sync
 */
@Database(
    entities = [
        MetricReadingEntity::class,
        SleepSessionEntity::class,
        DailySummaryEntity::class,
        DailyContextEntity::class,
        QuestionDefinitionEntity::class,
        QuestionResponseEntity::class,
        ActivityEntity::class,
        DeviceEntity::class,
        SyncSessionEntity::class,
        RawDeviceDataEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun metricReadingDao(): MetricReadingDao
    abstract fun sleepSessionDao(): SleepSessionDao
    abstract fun dailySummaryDao(): DailySummaryDao
    abstract fun dailyContextDao(): DailyContextDao
    abstract fun questionDefinitionDao(): QuestionDefinitionDao
    abstract fun questionResponseDao(): QuestionResponseDao
    abstract fun activityDao(): ActivityDao
    abstract fun deviceDao(): DeviceDao
    abstract fun syncSessionDao(): SyncSessionDao
    abstract fun rawDeviceDataDao(): RawDeviceDataDao

    companion object {
        const val DATABASE_NAME = "athlete_data.db"

        data class SeedQuestion(
            val name: String,
            val type: String,
            val sortOrder: Int,
            val isStarred: Boolean,
        )

        val LIFESTYLE_SEEDS = listOf(
            SeedQuestion("Fatigue",           "SCALE", 1, true),
            SeedQuestion("Stress",            "SCALE", 2, true),
            SeedQuestion("Motivation",        "SCALE", 3, true),
            SeedQuestion("Sleep quality",     "SCALE", 4, true),
            SeedQuestion("Performance feel",  "SCALE", 5, true),
            SeedQuestion("Energy",            "SCALE", 6, false),
            SeedQuestion("Focus",             "SCALE", 7, false),
            SeedQuestion("Muscle soreness",   "SCALE", 8, false),
            SeedQuestion("Mental clarity",    "SCALE", 9, false),
        )

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `question_definitions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `is_visible` INTEGER NOT NULL DEFAULT 1,
                        `sort_order` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `question_responses` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `question_id` INTEGER NOT NULL,
                        `date` TEXT NOT NULL,
                        `value` TEXT NOT NULL,
                        `recorded_at` INTEGER NOT NULL,
                        FOREIGN KEY(`question_id`) REFERENCES `question_definitions`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_question_responses_question_id` ON `question_responses`(`question_id`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_question_responses_question_id_date` ON `question_responses`(`question_id`, `date`)"
                )
                LIFESTYLE_SEEDS.forEach { seed ->
                    db.execSQL(
                        "INSERT OR IGNORE INTO question_definitions (name, type, category, is_visible, sort_order) VALUES (?, ?, 'LIFESTYLE', 1, ?)",
                        arrayOf(seed.name, seed.type, seed.sortOrder),
                    )
                }
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `question_definitions` ADD COLUMN `is_starred` INTEGER NOT NULL DEFAULT 0"
                )
                // Star the first 5 lifestyle questions (sort_order 1–5) that exist from the v3 seeder.
                db.execSQL(
                    "UPDATE `question_definitions` SET `is_starred` = 1 WHERE `category` = 'LIFESTYLE' AND `sort_order` <= 5"
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "index_metric_readings_dedup " +
                    "ON metric_readings(driver_id, metric_type, recorded_at)"
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {

                // devices must exist before sync_sessions (FK dependency)
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `devices` (
                        `id`           INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `ble_address`  TEXT NOT NULL,
                        `driver_id`    TEXT NOT NULL,
                        `display_name` TEXT NOT NULL,
                        `last_seen_ms` INTEGER,
                        `last_sync_ms` INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_devices_ble_address` " +
                    "ON `devices` (`ble_address`)"
                )

                // sync_sessions → devices(id)
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sync_sessions` (
                        `id`               INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `device_id`        INTEGER NOT NULL,
                        `driver_id`        TEXT NOT NULL,
                        `started_at`       INTEGER NOT NULL,
                        `ended_at`         INTEGER,
                        `status`           TEXT NOT NULL,
                        `records_imported` INTEGER NOT NULL DEFAULT 0,
                        `error_message`    TEXT,
                        FOREIGN KEY(`device_id`) REFERENCES `devices`(`id`)
                            ON UPDATE NO ACTION ON DELETE NO ACTION
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sync_sessions_device_id_started_at` " +
                    "ON `sync_sessions` (`device_id`, `started_at` DESC)"
                )

                // raw_device_data → sync_sessions(id)
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `raw_device_data` (
                        `id`                  INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sync_session_id`     INTEGER NOT NULL,
                        `characteristic_uuid` TEXT NOT NULL,
                        `payload`             TEXT NOT NULL,
                        `received_at`         INTEGER NOT NULL,
                        FOREIGN KEY(`sync_session_id`) REFERENCES `sync_sessions`(`id`)
                            ON UPDATE NO ACTION ON DELETE NO ACTION
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_raw_device_data_sync_session_id` " +
                    "ON `raw_device_data` (`sync_session_id`)"
                )

                // activities has no FK dependencies
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `activities` (
                        `id`               INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `start_time`       INTEGER NOT NULL,
                        `end_time`         INTEGER NOT NULL,
                        `duration_minutes` INTEGER NOT NULL,
                        `device_name`      TEXT NOT NULL,
                        `user_category`    TEXT,
                        `notes`            TEXT,
                        `source`           TEXT NOT NULL,
                        `driver_id`        TEXT,
                        `avg_hr_bpm`       REAL,
                        `max_hr_bpm`       REAL,
                        `min_hr_bpm`       REAL,
                        `calories`         REAL,
                        `active_calories`  REAL,
                        `distance_meters`  REAL,
                        `steps`            INTEGER,
                        `hr_zones_json`    TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_activities_driver_id_start_time` " +
                    "ON `activities` (`driver_id`, `start_time`)"
                )
            }
        }
    }
}
