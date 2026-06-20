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
 *   7 — added last_battery_pct to devices; battery is device metadata, not a MetricReading
 *   8 — unique dedup index on sleep_sessions(driver_id, date); index on activities(driver_id, start_time) made unique
 *   9 — one-time cleanup of corrupt sleep_sessions written during mid-sleep syncs (end <= start or duration < 1 min)
 *  10 — added packets_received and synced_before_quiescence to sync_sessions for stream-completeness heuristic
 *  11 — added dedicated typed time-series tables (hr, hrv, spo2, respiration, skin_temp, steps,
 *       blood_pressure, glucose, active_cal, total_cal, sleep_stages); removed stages_json from
 *       sleep_sessions; added new columns to daily_summary; renamed metric_readings to
 *       metric_readings_staging
 *  12 — QuestionResponseEntity.date re-typed from String to LocalDate in Kotlin; no SQLite schema
 *       change (TypeConverter stores LocalDate as ISO-8601 TEXT, identical to prior storage)
 *  13 — added widget_layout table for configurable Dashboard widget grid; seeded with default layout
 */
@Database(
    entities = [
        MetricReadingStagingEntity::class,
        SleepSessionEntity::class,
        DailySummaryEntity::class,
        DailyContextEntity::class,
        QuestionDefinitionEntity::class,
        QuestionResponseEntity::class,
        ActivityEntity::class,
        DeviceEntity::class,
        SyncSessionEntity::class,
        RawDeviceDataEntity::class,
        HrReadingEntity::class,
        HrvReadingEntity::class,
        SpO2ReadingEntity::class,
        RespirationReadingEntity::class,
        SkinTempReadingEntity::class,
        StepsReadingEntity::class,
        BloodPressureReadingEntity::class,
        GlucoseReadingEntity::class,
        ActiveCalorieReadingEntity::class,
        TotalCalorieReadingEntity::class,
        SleepStageEntity::class,
        WidgetLayoutEntity::class,
    ],
    version = 13,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun metricReadingStagingDao(): MetricReadingStagingDao
    abstract fun sleepSessionDao(): SleepSessionDao
    abstract fun dailySummaryDao(): DailySummaryDao
    abstract fun dailyContextDao(): DailyContextDao
    abstract fun questionDefinitionDao(): QuestionDefinitionDao
    abstract fun questionResponseDao(): QuestionResponseDao
    abstract fun activityDao(): ActivityDao
    abstract fun deviceDao(): DeviceDao
    abstract fun syncSessionDao(): SyncSessionDao
    abstract fun rawDeviceDataDao(): RawDeviceDataDao
    abstract fun hrReadingDao(): HrReadingDao
    abstract fun hrvReadingDao(): HrvReadingDao
    abstract fun spO2ReadingDao(): SpO2ReadingDao
    abstract fun respirationReadingDao(): RespirationReadingDao
    abstract fun skinTempReadingDao(): SkinTempReadingDao
    abstract fun stepsReadingDao(): StepsReadingDao
    abstract fun bloodPressureReadingDao(): BloodPressureReadingDao
    abstract fun glucoseReadingDao(): GlucoseReadingDao
    abstract fun activeCalorieReadingDao(): ActiveCalorieReadingDao
    abstract fun totalCalorieReadingDao(): TotalCalorieReadingDao
    abstract fun sleepStageDao(): SleepStageDao
    abstract fun widgetLayoutDao(): WidgetLayoutDao

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
            SeedQuestion("Sleep Quality",     "SCALE", 4, true),
            SeedQuestion("Performance Feel",  "SCALE", 5, true),
            SeedQuestion("Energy",            "SCALE", 6, false),
            SeedQuestion("Focus",             "SCALE", 7, false),
            SeedQuestion("Muscle Soreness",   "SCALE", 8, false),
            SeedQuestion("Mental Clarity",    "SCALE", 9, false),
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

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `devices` ADD COLUMN `last_battery_pct` INTEGER")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_sleep_sessions_dedup " +
                    "ON sleep_sessions(driver_id, date)"
                )
                db.execSQL("DROP INDEX IF EXISTS index_activities_driver_id_start_time")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_activities_dedup " +
                    "ON activities(driver_id, start_time)"
                )
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    DELETE FROM sleep_sessions
                    WHERE sleep_end_ms = 0
                       OR sleep_end_ms <= sleep_start_ms
                       OR (sleep_end_ms - sleep_start_ms) < 60000
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sync_sessions` ADD COLUMN `packets_received` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `sync_sessions` ADD COLUMN `synced_before_quiescence` INTEGER NOT NULL DEFAULT 0")
            }
        }

        // NOTE: Any metric_readings_staging rows written before the RESPIRATORY_RATE → RESPIRATION
        // deduplication fix (where metric_type = 'RESPIRATORY_RATE') will NOT be automatically
        // moved to respiration_readings by this migration. Those rows remain in
        // metric_readings_staging with the now-invalid type string and would require manual
        // reprocessing (e.g. UPDATE metric_readings_staging SET metric_type = 'RESPIRATION' …
        // followed by re-running the routing logic) to land in the correct table.
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {

                // ── Drop phase (child tables first) ───────────────────────────

                db.execSQL("DROP TABLE IF EXISTS `raw_device_data`")
                db.execSQL("DROP TABLE IF EXISTS `sync_sessions`")
                db.execSQL("DROP TABLE IF EXISTS `question_responses`")
                // These tables did not exist in schema v10. The drops are defensive idempotency
                // guards for partial-failure recovery — if a previous migration attempt created
                // any of these tables before failing, this ensures they are cleared before the
                // clean recreate phase. On a clean v10 database all of these are no-ops.
                db.execSQL("DROP TABLE IF EXISTS `sleep_stages`")
                db.execSQL("DROP TABLE IF EXISTS `activities`")
                db.execSQL("DROP TABLE IF EXISTS `metric_readings`")
                db.execSQL("DROP TABLE IF EXISTS `metric_readings_staging`")
                db.execSQL("DROP TABLE IF EXISTS `hr_readings`")
                db.execSQL("DROP TABLE IF EXISTS `hrv_readings`")
                db.execSQL("DROP TABLE IF EXISTS `spo2_readings`")
                db.execSQL("DROP TABLE IF EXISTS `respiration_readings`")
                db.execSQL("DROP TABLE IF EXISTS `skin_temp_readings`")
                db.execSQL("DROP TABLE IF EXISTS `steps_readings`")
                db.execSQL("DROP TABLE IF EXISTS `blood_pressure_readings`")
                db.execSQL("DROP TABLE IF EXISTS `glucose_readings`")
                db.execSQL("DROP TABLE IF EXISTS `active_calorie_readings`")
                db.execSQL("DROP TABLE IF EXISTS `total_calorie_readings`")
                db.execSQL("DROP TABLE IF EXISTS `sleep_sessions`")
                db.execSQL("DROP TABLE IF EXISTS `daily_summary`")
                db.execSQL("DROP TABLE IF EXISTS `daily_context`")
                db.execSQL("DROP TABLE IF EXISTS `devices`")
                db.execSQL("DROP TABLE IF EXISTS `question_definitions`")

                // ── Recreate phase (parents before children) ──────────────────

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `question_definitions` (
                        `id`         INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name`       TEXT NOT NULL,
                        `type`       TEXT NOT NULL,
                        `category`   TEXT NOT NULL,
                        `is_visible` INTEGER NOT NULL DEFAULT 1,
                        `sort_order` INTEGER NOT NULL,
                        `is_starred` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `question_responses` (
                        `id`          INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `question_id` INTEGER NOT NULL,
                        `date`        TEXT NOT NULL,
                        `value`       TEXT NOT NULL,
                        `recorded_at` INTEGER NOT NULL,
                        FOREIGN KEY(`question_id`) REFERENCES `question_definitions`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_question_responses_question_id` " +
                    "ON `question_responses` (`question_id`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_question_responses_question_id_date` " +
                    "ON `question_responses` (`question_id`, `date`)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `devices` (
                        `id`               INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `ble_address`      TEXT NOT NULL,
                        `driver_id`        TEXT NOT NULL,
                        `display_name`     TEXT NOT NULL,
                        `last_seen_ms`     INTEGER,
                        `last_sync_ms`     INTEGER,
                        `last_battery_pct` INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_devices_ble_address` " +
                    "ON `devices` (`ble_address`)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sync_sessions` (
                        `id`                       INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `device_id`                INTEGER NOT NULL,
                        `driver_id`                TEXT NOT NULL,
                        `started_at`               INTEGER NOT NULL,
                        `ended_at`                 INTEGER,
                        `status`                   TEXT NOT NULL,
                        `records_imported`         INTEGER NOT NULL DEFAULT 0,
                        `error_message`            TEXT,
                        `packets_received`         INTEGER NOT NULL DEFAULT 0,
                        `synced_before_quiescence` INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(`device_id`) REFERENCES `devices`(`id`)
                            ON UPDATE NO ACTION ON DELETE NO ACTION
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sync_sessions_device_id_started_at` " +
                    "ON `sync_sessions` (`device_id` ASC, `started_at` DESC)"
                )

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
                // MIGRATION_7_8 renamed this index from the Room auto-generated name
                // index_activities_driver_id_start_time to index_activities_dedup. This migration
                // reverts to the Room auto-generated name because ActivityEntity has no explicit
                // index name annotation. If index_activities_dedup is ever added to ActivityEntity,
                // update this comment and write a new migration to rename the index.
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_activities_driver_id_start_time` " +
                    "ON `activities` (`driver_id`, `start_time`)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sleep_sessions` (
                        `id`               INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `date`             TEXT NOT NULL,
                        `sleep_start_ms`   INTEGER NOT NULL,
                        `sleep_end_ms`     INTEGER NOT NULL,
                        `duration_minutes` INTEGER NOT NULL,
                        `source`           TEXT NOT NULL,
                        `driver_id`        TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_sleep_sessions_driver_id_date` " +
                    "ON `sleep_sessions` (`driver_id`, `date`)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sleep_stages` (
                        `id`                  INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `session_id`          INTEGER NOT NULL,
                        `stage`               TEXT NOT NULL,
                        `start_ms`            INTEGER NOT NULL,
                        `end_ms`              INTEGER NOT NULL,
                        `duration_minutes`    INTEGER NOT NULL,
                        `source`              TEXT NOT NULL,
                        `driver_id`           TEXT,
                        `computed_by_version` INTEGER NOT NULL,
                        FOREIGN KEY(`session_id`) REFERENCES `sleep_sessions`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_sleep_stages_session_id_start_ms` " +
                    "ON `sleep_stages` (`session_id`, `start_ms`)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `daily_summary` (
                        `date`                 TEXT NOT NULL PRIMARY KEY,
                        `avg_hr_bpm`           REAL,
                        `resting_hr_bpm`       REAL,
                        `avg_hrv_ms`           REAL,
                        `morning_hrv_ms`       REAL,
                        `avg_spo2_pct`         REAL,
                        `steps`                INTEGER,
                        `sleep_minutes`        INTEGER,
                        `sleep_deep_minutes`   INTEGER,
                        `sleep_light_minutes`  INTEGER,
                        `sleep_rem_minutes`    INTEGER,
                        `sleep_awake_minutes`  INTEGER,
                        `skin_temp_avg_c`      REAL,
                        `skin_temp_min_c`      REAL,
                        `skin_temp_max_c`      REAL,
                        `respiration_avg`      REAL,
                        `hrv_min_ms`           REAL,
                        `hrv_max_ms`           REAL,
                        `spo2_min_pct`         REAL,
                        `spo2_max_pct`         REAL,
                        `steps_active_minutes` INTEGER,
                        `total_calories`       REAL,
                        `active_calories`      REAL,
                        `computed_by_version`  INTEGER NOT NULL DEFAULT 0,
                        `source`               TEXT NOT NULL,
                        `computed_at`          INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `daily_context` (
                        `date`             TEXT NOT NULL PRIMARY KEY,
                        `fatigue`          INTEGER,
                        `stress`           INTEGER,
                        `motivation`       INTEGER,
                        `sleep_quality`    INTEGER,
                        `performance_feel` INTEGER,
                        `is_ill`           INTEGER NOT NULL DEFAULT 0,
                        `illness_notes`    TEXT,
                        `habits_json`      TEXT,
                        `weight_kg`        REAL,
                        `body_fat_pct`     REAL,
                        `notes`            TEXT,
                        `updated_at`       INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `metric_readings_staging` (
                        `id`          INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `metric_type` TEXT NOT NULL,
                        `value`       REAL NOT NULL,
                        `unit`        TEXT NOT NULL,
                        `recorded_at` INTEGER NOT NULL,
                        `created_at`  INTEGER NOT NULL,
                        `source`      TEXT NOT NULL,
                        `driver_id`   TEXT,
                        `confidence`  REAL,
                        `meta_json`   TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_metric_readings_staging_metric_type_recorded_at` " +
                    "ON `metric_readings_staging` (`metric_type` ASC, `recorded_at` DESC)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_metric_readings_staging_driver_id_metric_type_recorded_at` " +
                    "ON `metric_readings_staging` (`driver_id`, `metric_type`, `recorded_at`)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `hr_readings` (
                        `id`          INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `recorded_at` INTEGER NOT NULL,
                        `created_at`  INTEGER NOT NULL,
                        `source`      TEXT NOT NULL,
                        `driver_id`   TEXT,
                        `confidence`  REAL,
                        `meta_json`   TEXT,
                        `bpm`         INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_hr_readings_driver_id_recorded_at` " +
                    "ON `hr_readings` (`driver_id`, `recorded_at`)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `hrv_readings` (
                        `id`                  INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `recorded_at`         INTEGER NOT NULL,
                        `created_at`          INTEGER NOT NULL,
                        `source`              TEXT NOT NULL,
                        `driver_id`           TEXT,
                        `confidence`          REAL,
                        `meta_json`           TEXT,
                        `rmssd_ms`            REAL NOT NULL,
                        `computed_by_version` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_hrv_readings_driver_id_recorded_at` " +
                    "ON `hrv_readings` (`driver_id`, `recorded_at`)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `spo2_readings` (
                        `id`          INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `recorded_at` INTEGER NOT NULL,
                        `created_at`  INTEGER NOT NULL,
                        `source`      TEXT NOT NULL,
                        `driver_id`   TEXT,
                        `confidence`  REAL,
                        `meta_json`   TEXT,
                        `percentage`  REAL NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_spo2_readings_driver_id_recorded_at` " +
                    "ON `spo2_readings` (`driver_id`, `recorded_at`)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `respiration_readings` (
                        `id`                 INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `recorded_at`        INTEGER NOT NULL,
                        `created_at`         INTEGER NOT NULL,
                        `source`             TEXT NOT NULL,
                        `driver_id`          TEXT,
                        `confidence`         REAL,
                        `meta_json`          TEXT,
                        `breaths_per_minute` REAL NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_respiration_readings_driver_id_recorded_at` " +
                    "ON `respiration_readings` (`driver_id`, `recorded_at`)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `skin_temp_readings` (
                        `id`          INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `recorded_at` INTEGER NOT NULL,
                        `created_at`  INTEGER NOT NULL,
                        `source`      TEXT NOT NULL,
                        `driver_id`   TEXT,
                        `confidence`  REAL,
                        `meta_json`   TEXT,
                        `celsius`     REAL NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_skin_temp_readings_driver_id_recorded_at` " +
                    "ON `skin_temp_readings` (`driver_id`, `recorded_at`)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `steps_readings` (
                        `id`               INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `recorded_at`      INTEGER NOT NULL,
                        `created_at`       INTEGER NOT NULL,
                        `source`           TEXT NOT NULL,
                        `driver_id`        TEXT,
                        `confidence`       REAL,
                        `meta_json`        TEXT,
                        `cumulative_steps` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_steps_readings_driver_id_recorded_at` " +
                    "ON `steps_readings` (`driver_id`, `recorded_at`)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `blood_pressure_readings` (
                        `id`          INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `recorded_at` INTEGER NOT NULL,
                        `created_at`  INTEGER NOT NULL,
                        `source`      TEXT NOT NULL,
                        `driver_id`   TEXT,
                        `confidence`  REAL,
                        `meta_json`   TEXT,
                        `systolic`    INTEGER NOT NULL,
                        `diastolic`   INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_blood_pressure_readings_driver_id_recorded_at` " +
                    "ON `blood_pressure_readings` (`driver_id`, `recorded_at`)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `glucose_readings` (
                        `id`          INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `recorded_at` INTEGER NOT NULL,
                        `created_at`  INTEGER NOT NULL,
                        `source`      TEXT NOT NULL,
                        `driver_id`   TEXT,
                        `confidence`  REAL,
                        `meta_json`   TEXT,
                        `value`       REAL NOT NULL,
                        `unit`        TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_glucose_readings_driver_id_recorded_at` " +
                    "ON `glucose_readings` (`driver_id`, `recorded_at`)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `active_calorie_readings` (
                        `id`          INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `recorded_at` INTEGER NOT NULL,
                        `created_at`  INTEGER NOT NULL,
                        `source`      TEXT NOT NULL,
                        `driver_id`   TEXT,
                        `confidence`  REAL,
                        `meta_json`   TEXT,
                        `calories`    REAL NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_active_calorie_readings_driver_id_recorded_at` " +
                    "ON `active_calorie_readings` (`driver_id`, `recorded_at`)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `total_calorie_readings` (
                        `id`          INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `recorded_at` INTEGER NOT NULL,
                        `created_at`  INTEGER NOT NULL,
                        `source`      TEXT NOT NULL,
                        `driver_id`   TEXT,
                        `confidence`  REAL,
                        `meta_json`   TEXT,
                        `calories`    REAL NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_total_calorie_readings_driver_id_recorded_at` " +
                    "ON `total_calorie_readings` (`driver_id`, `recorded_at`)"
                )

                // ── Seed question_definitions ─────────────────────────────────

                LIFESTYLE_SEEDS.forEach { seed ->
                    db.execSQL(
                        "INSERT OR IGNORE INTO question_definitions (name, type, category, is_visible, is_starred, sort_order) VALUES (?, ?, 'LIFESTYLE', 1, ?, ?)",
                        arrayOf(seed.name, seed.type, if (seed.isStarred) 1 else 0, seed.sortOrder),
                    )
                }
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // No-op: QuestionResponseEntity.date was re-typed from String to LocalDate in
                // Kotlin. The TypeConverter stores LocalDate as ISO-8601 TEXT ("YYYY-MM-DD"),
                // which is exactly the format the old String field already used. The SQLite
                // column definition stays `TEXT NOT NULL` — no data rewriting is required.
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `widget_layout` (
                        `id`          INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `widget_type` TEXT NOT NULL,
                        `size`        TEXT NOT NULL,
                        `sort_order`  INTEGER NOT NULL,
                        `extra_id`    INTEGER
                    )
                    """.trimIndent()
                )
                val defaults = listOf(
                    Triple("HR",                 "SMALL", 0),
                    Triple("HRV",                "SMALL", 1),
                    Triple("RHR",                "SMALL", 2),
                    Triple("SLEEP",              "SMALL", 3),
                    Triple("SPO2",               "SMALL", 4),
                    Triple("STEPS",              "SMALL", 5),
                    Triple("WEIGHT",             "WIDE",  6),
                    Triple("STARRED_LIFESTYLE",  "WIDE",  7),
                    Triple("STARRED_HABITS",     "WIDE",  8),
                    Triple("ACTIVITIES",         "WIDE",  9),
                )
                defaults.forEach { (type, size, order) ->
                    db.execSQL(
                        "INSERT INTO widget_layout (widget_type, size, sort_order) VALUES (?, ?, ?)",
                        arrayOf(type, size, order)
                    )
                }
            }
        }
    }
}
