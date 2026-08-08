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
 *  14 — added user_profile table (single-row athlete profile with biometrics and HR zones)
 *  15 — added avg_systolic_mm_hg, avg_diastolic_mm_hg, avg_glucose_mmol_l to daily_summary // BP-GLUCOSE-SUMMARY
 *  16 — replaced daily_summary.morning_hrv_ms with overnight_hrv_ms (overnight-window HRV
 *       computed in a later change; old morning values are not reinterpreted)
 *  17 — added baseline_range table for computed per-metric baseline ranges (mean ± 1 SD
 *       over a rolling window)
 *  18 — added baseline_window_config table for per-BaselineMetric overrides of the
 *       baseline rolling window and minimum-days requirement (window_days/minimum_days
 *       nullable so either can be overridden independently; see BaselineWindowConfigRepository)
 *  19 — replaced widget_layout's widget_type/size/extra_id columns with
 *       template_id/col_span/row_span (skyline-packer grid rewrite: fixed 2 columns,
 *       colSpan 1-2, rowSpan 1-4, position computed from sequence_order at render time,
 *       not persisted); table is dropped and recreated, reseeded with the same 10-widget
 *       default layout as MIGRATION_12_13 — existing custom placements/sizes are not
 *       migrated (acceptable per product decision)
 *  20 — added metric_daily_stats table: one row per (day, BaselineMetric) holding a
 *       trailing-window mean/std-dev computed by a MetricStatsCalculator; additive only,
 *       not yet read or written by any app code (see MetricDailyStatsEntity)
 *  21 — dropped baseline_range: superseded by metric_daily_stats (per-day, point-in-time),
 *       which the baseline-band UI now reads exclusively; RoomBaselineRepository/BaselineDao/
 *       BaselineEntity removed
 *  22 — added nullable device_id (INTEGER) column to 13 reading/session/activity tables
 *       (physical devices.id, not the driver); backfilled from devices.id where driver_id
 *       maps to exactly one device row. Additive only — no index changes; existing
 *       driver_id-based unique/dedup indexes are unchanged.
 *  23 — replaced the driver_id-based UNIQUE dedup indexes on the same 13 tables with
 *       device_id-based UNIQUE indexes (fixes two physical units sharing a driver
 *       colliding at the same timestamp). driver_id remains a plain provenance column,
 *       not removed. Index-only migration — no table rebuilt, no row deleted or merged.
 *       Safe without a pre-merge step: under the old (driver_id, X) uniqueness every
 *       attributed row's device_id was already 1:1 with its driver_id (v22's
 *       single-device backfill), so CREATE UNIQUE INDEX on (device_id, X) cannot collide
 *       for attributed rows; NULL-device rows are pairwise distinct under SQLite's
 *       NULL-in-unique-index handling, so they can't collide either.
 *  24 — added is_primary and auto_sync_enabled (INTEGER, default 0/1) to devices for the
 *       multi-device "bring your own band" primary-device model; backfilled is_primary onto
 *       the most-recently-active device (same heuristic autoConnectOnStartup used before this
 *       migration), auto_sync_enabled defaults to 1 for all existing devices unchanged.
 *  25 — added cdm_associated (INTEGER, default 0) to devices: tracks whether a
 *       CompanionDeviceManager association was granted for this device at pairing time, so
 *       BackgroundSyncWorker/SyncCompanionDeviceService know which devices can be woken via
 *       CDM presence observation vs. rely solely on the periodic Worker.
 *  26 — data-only cleanup on the 10 typed reading tables (hr, hrv, spo2, respiration,
 *       skin_temp, steps, blood_pressure, glucose, active_cal, total_cal): collapses
 *       duplicate device_id IS NULL rows sharing the same recorded_at (keeping the
 *       highest id, i.e. last-write-wins, matching REPLACE semantics), drops any
 *       remaining NULL row that duplicates an already-attributed row, then backfills
 *       device_id on the rest using the same single-device-per-driver_id heuristic as
 *       v22. Fixes historical duplicate rows left behind by a bug where the EOS device
 *       sync path never set device_id before insert, so the (device_id, recorded_at)
 *       unique index (added in v22/v23) never matched across syncs and REPLACE never
 *       fired. No schema/index change — dedup-then-backfill only, so the constraint
 *       introduced in v23 can't be violated by newly-attributed rows.
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
        UserProfileEntity::class,
        BaselineWindowConfigEntity::class,
        MetricDailyStatsEntity::class,
    ],
    version = 26,
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

    /** Provides DAO access to the single-row athlete profile table. */
    abstract fun userProfileDao(): UserProfileDao

    abstract fun baselineWindowConfigDao(): BaselineWindowConfigDao

    abstract fun metricDailyStatsDao(): MetricDailyStatsDao

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

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `user_profile` (
                        `id`                     INTEGER NOT NULL PRIMARY KEY,
                        `name`                   TEXT,
                        `date_of_birth`          TEXT,
                        `biological_sex`         TEXT,
                        `height_cm`              REAL,
                        `weight_kg`              REAL,
                        `stride_length_cm`       REAL,
                        `wrist_circumference_mm` REAL,
                        `resting_metabolic_rate` REAL,
                        `vo2_max`                REAL,
                        `max_hr`                 INTEGER,
                        `hr_zones_json`          TEXT,
                        `updated_at`             INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) { // BP-GLUCOSE-SUMMARY
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE daily_summary ADD COLUMN avg_systolic_mm_hg REAL") // BP-GLUCOSE-SUMMARY
                db.execSQL("ALTER TABLE daily_summary ADD COLUMN avg_diastolic_mm_hg REAL") // BP-GLUCOSE-SUMMARY
                db.execSQL("ALTER TABLE daily_summary ADD COLUMN avg_glucose_mmol_l REAL")  // BP-GLUCOSE-SUMMARY
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

        // Replaces morning_hrv_ms with overnight_hrv_ms. SQLite can't DROP COLUMN before
        // 3.35.5, so this recreates the table via the standard Room drop/recreate pattern.
        // morning_hrv_ms values are intentionally NOT copied into overnight_hrv_ms — the
        // two fields have different semantics and old values must not be reinterpreted.
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `daily_summary_new` (
                        `date`                 TEXT NOT NULL PRIMARY KEY,
                        `avg_hr_bpm`           REAL,
                        `resting_hr_bpm`       REAL,
                        `avg_hrv_ms`           REAL,
                        `overnight_hrv_ms`     REAL,
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
                        `computed_at`          INTEGER NOT NULL,
                        `avg_systolic_mm_hg`   REAL,
                        `avg_diastolic_mm_hg`  REAL,
                        `avg_glucose_mmol_l`   REAL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `daily_summary_new` (
                        `date`, `avg_hr_bpm`, `resting_hr_bpm`, `avg_hrv_ms`, `overnight_hrv_ms`,
                        `avg_spo2_pct`, `steps`, `sleep_minutes`, `sleep_deep_minutes`,
                        `sleep_light_minutes`, `sleep_rem_minutes`, `sleep_awake_minutes`,
                        `skin_temp_avg_c`, `skin_temp_min_c`, `skin_temp_max_c`, `respiration_avg`,
                        `hrv_min_ms`, `hrv_max_ms`, `spo2_min_pct`, `spo2_max_pct`,
                        `steps_active_minutes`, `total_calories`, `active_calories`,
                        `computed_by_version`, `source`, `computed_at`,
                        `avg_systolic_mm_hg`, `avg_diastolic_mm_hg`, `avg_glucose_mmol_l`
                    )
                    SELECT
                        `date`, `avg_hr_bpm`, `resting_hr_bpm`, `avg_hrv_ms`, NULL,
                        `avg_spo2_pct`, `steps`, `sleep_minutes`, `sleep_deep_minutes`,
                        `sleep_light_minutes`, `sleep_rem_minutes`, `sleep_awake_minutes`,
                        `skin_temp_avg_c`, `skin_temp_min_c`, `skin_temp_max_c`, `respiration_avg`,
                        `hrv_min_ms`, `hrv_max_ms`, `spo2_min_pct`, `spo2_max_pct`,
                        `steps_active_minutes`, `total_calories`, `active_calories`,
                        `computed_by_version`, `source`, `computed_at`,
                        `avg_systolic_mm_hg`, `avg_diastolic_mm_hg`, `avg_glucose_mmol_l`
                    FROM `daily_summary`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `daily_summary`")
                db.execSQL("ALTER TABLE `daily_summary_new` RENAME TO `daily_summary`")
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `baseline_range` (
                        `metric_type`   TEXT NOT NULL PRIMARY KEY,
                        `mean`          REAL NOT NULL,
                        `std_dev`       REAL NOT NULL,
                        `lower`         REAL NOT NULL,
                        `upper`         REAL NOT NULL,
                        `window_days`   INTEGER NOT NULL,
                        `calculated_at` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `baseline_window_config` (
                        `metric_type`  TEXT NOT NULL PRIMARY KEY,
                        `window_days`  INTEGER,
                        `minimum_days` INTEGER,
                        `updated_at`   INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        internal data class DefaultWidget(val templateId: String, val colSpan: Int, val rowSpan: Int, val sequenceOrder: Int)

        /**
         * Single source of truth for the default dashboard layout, shared by [MIGRATION_18_19]
         * and the fresh-install `onCreate` seeding in `DatabaseModule` (Room only runs
         * migrations on upgrade, never on a brand-new database).
         */
        internal val DEFAULT_WIDGET_LAYOUT = listOf(
            DefaultWidget("HR", 1, 1, 0),
            DefaultWidget("HRV", 1, 1, 1),
            DefaultWidget("RHR", 1, 1, 2),
            DefaultWidget("SLEEP_SUMMARY_SMALL", 1, 1, 3),
            DefaultWidget("SPO2", 1, 1, 4),
            DefaultWidget("STEPS", 1, 1, 5),
            DefaultWidget("WEIGHT", 2, 1, 6),
            DefaultWidget("STARRED_LIFESTYLE_BAR", 2, 1, 7),
            DefaultWidget("CUSTOM_QUESTIONS_BAR", 2, 1, 8),
            DefaultWidget("ACTIVITIES", 2, 1, 9),
        )

        // Replaces widget_layout's widget_type/size/extra_id discriminator columns with
        // template_id/col_span/row_span for the skyline-packer grid rewrite. No per-row
        // migration is attempted (widget_type -> template_id isn't a 1:1 rename, and the old
        // binary size doesn't map cleanly onto arbitrary colSpan/rowSpan) - the table is
        // dropped and reseeded with the same 10-widget default layout MIGRATION_12_13 used.
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `widget_layout`")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `widget_layout` (
                        `id`             INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `template_id`    TEXT NOT NULL,
                        `col_span`       INTEGER NOT NULL,
                        `row_span`       INTEGER NOT NULL,
                        `sequence_order` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                DEFAULT_WIDGET_LAYOUT.forEach { (template, colSpan, rowSpan, order) ->
                    db.execSQL(
                        "INSERT INTO widget_layout (template_id, col_span, row_span, sequence_order) VALUES (?, ?, ?, ?)",
                        arrayOf(template, colSpan, rowSpan, order)
                    )
                }
            }
        }

        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `metric_daily_stats` (
                        `summary_date`  TEXT NOT NULL,
                        `metric_type`   TEXT NOT NULL,
                        `mean`          REAL,
                        `std_dev`       REAL,
                        `mean_pct`      REAL,
                        `sample_count`  INTEGER NOT NULL,
                        `window_days`   INTEGER NOT NULL,
                        `minimum_days`  INTEGER NOT NULL,
                        `config_hash`   TEXT NOT NULL,
                        `computed_at`   INTEGER NOT NULL,
                        PRIMARY KEY(`summary_date`, `metric_type`)
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `baseline_range`")
            }
        }

        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // --- Add nullable device_id (physical devices.id, NOT the driver) to 13 tables ---
                db.execSQL("ALTER TABLE `hr_readings` ADD COLUMN `device_id` INTEGER")
                db.execSQL("ALTER TABLE `hrv_readings` ADD COLUMN `device_id` INTEGER")
                db.execSQL("ALTER TABLE `spo2_readings` ADD COLUMN `device_id` INTEGER")
                db.execSQL("ALTER TABLE `respiration_readings` ADD COLUMN `device_id` INTEGER")
                db.execSQL("ALTER TABLE `skin_temp_readings` ADD COLUMN `device_id` INTEGER")
                db.execSQL("ALTER TABLE `steps_readings` ADD COLUMN `device_id` INTEGER")
                db.execSQL("ALTER TABLE `blood_pressure_readings` ADD COLUMN `device_id` INTEGER")
                db.execSQL("ALTER TABLE `glucose_readings` ADD COLUMN `device_id` INTEGER")
                db.execSQL("ALTER TABLE `active_calorie_readings` ADD COLUMN `device_id` INTEGER")
                db.execSQL("ALTER TABLE `total_calorie_readings` ADD COLUMN `device_id` INTEGER")
                db.execSQL("ALTER TABLE `metric_readings_staging` ADD COLUMN `device_id` INTEGER")
                db.execSQL("ALTER TABLE `sleep_sessions` ADD COLUMN `device_id` INTEGER")
                db.execSQL("ALTER TABLE `activities` ADD COLUMN `device_id` INTEGER")

                // --- Backfill: only when driver_id maps to EXACTLY ONE device row. ---
                // devices.driver_id is NOT unique (two physical units can share a driver_id),
                // so a plain join is unsafe; ambiguous/orphaned/null-driver rows stay NULL.
                db.execSQL(
                    """
                    UPDATE `hr_readings`
                    SET device_id = (SELECT d.id FROM devices d WHERE d.driver_id = `hr_readings`.driver_id)
                    WHERE `hr_readings`.driver_id IS NOT NULL
                      AND (SELECT COUNT(*) FROM devices d2 WHERE d2.driver_id = `hr_readings`.driver_id) = 1
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE `hrv_readings`
                    SET device_id = (SELECT d.id FROM devices d WHERE d.driver_id = `hrv_readings`.driver_id)
                    WHERE `hrv_readings`.driver_id IS NOT NULL
                      AND (SELECT COUNT(*) FROM devices d2 WHERE d2.driver_id = `hrv_readings`.driver_id) = 1
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE `spo2_readings`
                    SET device_id = (SELECT d.id FROM devices d WHERE d.driver_id = `spo2_readings`.driver_id)
                    WHERE `spo2_readings`.driver_id IS NOT NULL
                      AND (SELECT COUNT(*) FROM devices d2 WHERE d2.driver_id = `spo2_readings`.driver_id) = 1
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE `respiration_readings`
                    SET device_id = (SELECT d.id FROM devices d WHERE d.driver_id = `respiration_readings`.driver_id)
                    WHERE `respiration_readings`.driver_id IS NOT NULL
                      AND (SELECT COUNT(*) FROM devices d2 WHERE d2.driver_id = `respiration_readings`.driver_id) = 1
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE `skin_temp_readings`
                    SET device_id = (SELECT d.id FROM devices d WHERE d.driver_id = `skin_temp_readings`.driver_id)
                    WHERE `skin_temp_readings`.driver_id IS NOT NULL
                      AND (SELECT COUNT(*) FROM devices d2 WHERE d2.driver_id = `skin_temp_readings`.driver_id) = 1
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE `steps_readings`
                    SET device_id = (SELECT d.id FROM devices d WHERE d.driver_id = `steps_readings`.driver_id)
                    WHERE `steps_readings`.driver_id IS NOT NULL
                      AND (SELECT COUNT(*) FROM devices d2 WHERE d2.driver_id = `steps_readings`.driver_id) = 1
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE `blood_pressure_readings`
                    SET device_id = (SELECT d.id FROM devices d WHERE d.driver_id = `blood_pressure_readings`.driver_id)
                    WHERE `blood_pressure_readings`.driver_id IS NOT NULL
                      AND (SELECT COUNT(*) FROM devices d2 WHERE d2.driver_id = `blood_pressure_readings`.driver_id) = 1
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE `glucose_readings`
                    SET device_id = (SELECT d.id FROM devices d WHERE d.driver_id = `glucose_readings`.driver_id)
                    WHERE `glucose_readings`.driver_id IS NOT NULL
                      AND (SELECT COUNT(*) FROM devices d2 WHERE d2.driver_id = `glucose_readings`.driver_id) = 1
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE `active_calorie_readings`
                    SET device_id = (SELECT d.id FROM devices d WHERE d.driver_id = `active_calorie_readings`.driver_id)
                    WHERE `active_calorie_readings`.driver_id IS NOT NULL
                      AND (SELECT COUNT(*) FROM devices d2 WHERE d2.driver_id = `active_calorie_readings`.driver_id) = 1
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE `total_calorie_readings`
                    SET device_id = (SELECT d.id FROM devices d WHERE d.driver_id = `total_calorie_readings`.driver_id)
                    WHERE `total_calorie_readings`.driver_id IS NOT NULL
                      AND (SELECT COUNT(*) FROM devices d2 WHERE d2.driver_id = `total_calorie_readings`.driver_id) = 1
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE `metric_readings_staging`
                    SET device_id = (SELECT d.id FROM devices d WHERE d.driver_id = `metric_readings_staging`.driver_id)
                    WHERE `metric_readings_staging`.driver_id IS NOT NULL
                      AND (SELECT COUNT(*) FROM devices d2 WHERE d2.driver_id = `metric_readings_staging`.driver_id) = 1
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE `sleep_sessions`
                    SET device_id = (SELECT d.id FROM devices d WHERE d.driver_id = `sleep_sessions`.driver_id)
                    WHERE `sleep_sessions`.driver_id IS NOT NULL
                      AND (SELECT COUNT(*) FROM devices d2 WHERE d2.driver_id = `sleep_sessions`.driver_id) = 1
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE `activities`
                    SET device_id = (SELECT d.id FROM devices d WHERE d.driver_id = `activities`.driver_id)
                    WHERE `activities`.driver_id IS NOT NULL
                      AND (SELECT COUNT(*) FROM devices d2 WHERE d2.driver_id = `activities`.driver_id) = 1
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // --- Replace driver_id-based UNIQUE dedup indexes with device_id-based ones. ---
                // Index-only: no ALTER TABLE, no data touched. Safe without pre-merging
                // duplicates -- see the v23 schema-history kdoc above for why.

                db.execSQL("DROP INDEX IF EXISTS `index_hr_readings_driver_id_recorded_at`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_hr_readings_device_id_recorded_at` " +
                        "ON `hr_readings` (`device_id`, `recorded_at`)"
                )

                db.execSQL("DROP INDEX IF EXISTS `index_hrv_readings_driver_id_recorded_at`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_hrv_readings_device_id_recorded_at` " +
                        "ON `hrv_readings` (`device_id`, `recorded_at`)"
                )

                db.execSQL("DROP INDEX IF EXISTS `index_spo2_readings_driver_id_recorded_at`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_spo2_readings_device_id_recorded_at` " +
                        "ON `spo2_readings` (`device_id`, `recorded_at`)"
                )

                db.execSQL("DROP INDEX IF EXISTS `index_respiration_readings_driver_id_recorded_at`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_respiration_readings_device_id_recorded_at` " +
                        "ON `respiration_readings` (`device_id`, `recorded_at`)"
                )

                db.execSQL("DROP INDEX IF EXISTS `index_skin_temp_readings_driver_id_recorded_at`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_skin_temp_readings_device_id_recorded_at` " +
                        "ON `skin_temp_readings` (`device_id`, `recorded_at`)"
                )

                db.execSQL("DROP INDEX IF EXISTS `index_steps_readings_driver_id_recorded_at`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_steps_readings_device_id_recorded_at` " +
                        "ON `steps_readings` (`device_id`, `recorded_at`)"
                )

                db.execSQL("DROP INDEX IF EXISTS `index_blood_pressure_readings_driver_id_recorded_at`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_blood_pressure_readings_device_id_recorded_at` " +
                        "ON `blood_pressure_readings` (`device_id`, `recorded_at`)"
                )

                db.execSQL("DROP INDEX IF EXISTS `index_glucose_readings_driver_id_recorded_at`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_glucose_readings_device_id_recorded_at` " +
                        "ON `glucose_readings` (`device_id`, `recorded_at`)"
                )

                db.execSQL("DROP INDEX IF EXISTS `index_active_calorie_readings_driver_id_recorded_at`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_active_calorie_readings_device_id_recorded_at` " +
                        "ON `active_calorie_readings` (`device_id`, `recorded_at`)"
                )

                db.execSQL("DROP INDEX IF EXISTS `index_total_calorie_readings_driver_id_recorded_at`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_total_calorie_readings_device_id_recorded_at` " +
                        "ON `total_calorie_readings` (`device_id`, `recorded_at`)"
                )

                // metric_readings_staging: only the (driver_id, metric_type, recorded_at) UNIQUE
                // index is replaced. index_metric_readings_staging_metric_type_recorded_at
                // (non-unique, ASC/DESC ordered) is untouched.
                db.execSQL("DROP INDEX IF EXISTS `index_metric_readings_staging_driver_id_metric_type_recorded_at`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_metric_readings_staging_device_id_metric_type_recorded_at` " +
                        "ON `metric_readings_staging` (`device_id`, `metric_type`, `recorded_at`)"
                )

                db.execSQL("DROP INDEX IF EXISTS `index_sleep_sessions_driver_id_date`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_sleep_sessions_device_id_date` " +
                        "ON `sleep_sessions` (`device_id`, `date`)"
                )

                db.execSQL("DROP INDEX IF EXISTS `index_activities_driver_id_start_time`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_activities_device_id_start_time` " +
                        "ON `activities` (`device_id`, `start_time`)"
                )
            }
        }

        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // auto_sync_enabled DEFAULT 1: SQLite backfills the default into existing rows
                // at ALTER time, so no separate UPDATE is needed to preserve today's
                // always-sync behavior.
                db.execSQL("ALTER TABLE `devices` ADD COLUMN `is_primary` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `devices` ADD COLUMN `auto_sync_enabled` INTEGER NOT NULL DEFAULT 1")

                // Backfill is_primary: the most recently active device becomes primary, same
                // heuristic autoConnectOnStartup used before this migration. Deterministic
                // tie-break (id ASC) guarantees exactly one row wins even when all timestamps
                // are NULL/equal. Zero devices -> subquery returns no row -> UPDATE affects
                // 0 rows (safe no-op).
                db.execSQL(
                    """
                    UPDATE `devices`
                    SET is_primary = 1
                    WHERE id = (
                        SELECT id FROM `devices`
                        ORDER BY COALESCE(last_sync_ms, last_seen_ms, 0) DESC, id ASC
                        LIMIT 1
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // DEFAULT 0: existing devices have no CDM association until the user re-pairs
                // or the app requests one retroactively; this is a "not yet associated" default,
                // not a claim that association was ever attempted.
                db.execSQL("ALTER TABLE `devices` ADD COLUMN `cdm_associated` INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // --- Historical duplicate cleanup for the 10 typed reading tables. ---
                // A now-fixed bug (EOS device sync path never set device_id before insert)
                // left behind duplicate device_id IS NULL rows for the same recorded_at,
                // because the (device_id, recorded_at) UNIQUE index added in v22/v23 never
                // matches two NULLs, so OnConflictStrategy.REPLACE never fired.
                //
                // Per table, in order:
                //   1) Collapse duplicate NULL-device_id rows sharing a recorded_at, keeping
                //      the highest id (last insert wins, matching REPLACE semantics).
                //   2) Delete any remaining NULL-device_id row that duplicates a row already
                //      correctly attributed to the single device its driver_id resolves to
                //      (e.g. written via triggerSync(), which always set device_id).
                //   3) Backfill device_id on the rows still NULL, using the same
                //      single-device-per-driver_id heuristic as v22. Ambiguous (driver_id
                //      shared by 2+ devices) or driver_id-less rows are deliberately left
                //      NULL, same as v22's policy.
                // Step 1 and 2 must run before step 3: introducing new non-NULL device_id
                // values into a live UNIQUE index without dedup-ing first can collide with
                // an existing row and abort the whole migration.

                db.execSQL(
                    "DELETE FROM `hr_readings` WHERE device_id IS NULL AND id NOT IN " +
                        "(SELECT MAX(id) FROM `hr_readings` WHERE device_id IS NULL GROUP BY recorded_at)"
                )
                db.execSQL(
                    """
                    DELETE FROM `hr_readings`
                    WHERE device_id IS NULL
                      AND driver_id IS NOT NULL
                      AND (SELECT COUNT(*) FROM devices d2 WHERE d2.driver_id = `hr_readings`.driver_id) = 1
                      AND EXISTS (
                        SELECT 1 FROM `hr_readings` t2
                        WHERE t2.device_id = (SELECT d.id FROM devices d WHERE d.driver_id = `hr_readings`.driver_id)
                          AND t2.recorded_at = `hr_readings`.recorded_at
                      )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE `hr_readings`
                    SET device_id = (SELECT d.id FROM devices d WHERE d.driver_id = `hr_readings`.driver_id)
                    WHERE driver_id IS NOT NULL
                      AND device_id IS NULL
                      AND (SELECT COUNT(*) FROM devices d2 WHERE d2.driver_id = `hr_readings`.driver_id) = 1
                    """.trimIndent()
                )

                db.execSQL(
                    "DELETE FROM `hrv_readings` WHERE device_id IS NULL AND id NOT IN " +
                        "(SELECT MAX(id) FROM `hrv_readings` WHERE device_id IS NULL GROUP BY recorded_at)"
                )
                db.execSQL(
                    """
                    DELETE FROM `hrv_readings`
                    WHERE device_id IS NULL
                      AND driver_id IS NOT NULL
                      AND (SELECT COUNT(*) FROM devices d2 WHERE d2.driver_id = `hrv_readings`.driver_id) = 1
                      AND EXISTS (
                        SELECT 1 FROM `hrv_readings` t2
                        WHERE t2.device_id = (SELECT d.id FROM devices d WHERE d.driver_id = `hrv_readings`.driver_id)
                          AND t2.recorded_at = `hrv_readings`.recorded_at
                      )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE `hrv_readings`
                    SET device_id = (SELECT d.id FROM devices d WHERE d.driver_id = `hrv_readings`.driver_id)
                    WHERE driver_id IS NOT NULL
                      AND device_id IS NULL
                      AND (SELECT COUNT(*) FROM devices d2 WHERE d2.driver_id = `hrv_readings`.driver_id) = 1
                    """.trimIndent()
                )

                db.execSQL(
                    "DELETE FROM `spo2_readings` WHERE device_id IS NULL AND id NOT IN " +
                        "(SELECT MAX(id) FROM `spo2_readings` WHERE device_id IS NULL GROUP BY recorded_at)"
                )
                db.execSQL(
                    """
                    DELETE FROM `spo2_readings`
                    WHERE device_id IS NULL
                      AND driver_id IS NOT NULL
                      AND (SELECT COUNT(*) FROM devices d2 WHERE d2.driver_id = `spo2_readings`.driver_id) = 1
                      AND EXISTS (
                        SELECT 1 FROM `spo2_readings` t2
                        WHERE t2.device_id = (SELECT d.id FROM devices d WHERE d.driver_id = `spo2_readings`.driver_id)
                          AND t2.recorded_at = `spo2_readings`.recorded_at
                      )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE `spo2_readings`
                    SET device_id = (SELECT d.id FROM devices d WHERE d.driver_id = `spo2_readings`.driver_id)
                    WHERE driver_id IS NOT NULL
                      AND device_id IS NULL
                      AND (SELECT COUNT(*) FROM devices d2 WHERE d2.driver_id = `spo2_readings`.driver_id) = 1
                    """.trimIndent()
                )

                db.execSQL(
                    "DELETE FROM `respiration_readings` WHERE device_id IS NULL AND id NOT IN " +
                        "(SELECT MAX(id) FROM `respiration_readings` WHERE device_id IS NULL GROUP BY recorded_at)"
                )
                db.execSQL(
                    """
                    DELETE FROM `respiration_readings`
                    WHERE device_id IS NULL
                      AND driver_id IS NOT NULL
                      AND (SELECT COUNT(*) FROM devices d2 WHERE d2.driver_id = `respiration_readings`.driver_id) = 1
                      AND EXISTS (
                        SELECT 1 FROM `respiration_readings` t2
                        WHERE t2.device_id = (SELECT d.id FROM devices d WHERE d.driver_id = `respiration_readings`.driver_id)
                          AND t2.recorded_at = `respiration_readings`.recorded_at
                      )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE `respiration_readings`
                    SET device_id = (SELECT d.id FROM devices d WHERE d.driver_id = `respiration_readings`.driver_id)
                    WHERE driver_id IS NOT NULL
                      AND device_id IS NULL
                      AND (SELECT COUNT(*) FROM devices d2 WHERE d2.driver_id = `respiration_readings`.driver_id) = 1
                    """.trimIndent()
                )

                db.execSQL(
                    "DELETE FROM `skin_temp_readings` WHERE device_id IS NULL AND id NOT IN " +
                        "(SELECT MAX(id) FROM `skin_temp_readings` WHERE device_id IS NULL GROUP BY recorded_at)"
                )
                db.execSQL(
                    """
                    DELETE FROM `skin_temp_readings`
                    WHERE device_id IS NULL
                      AND driver_id IS NOT NULL
                      AND (SELECT COUNT(*) FROM devices d2 WHERE d2.driver_id = `skin_temp_readings`.driver_id) = 1
                      AND EXISTS (
                        SELECT 1 FROM `skin_temp_readings` t2
                        WHERE t2.device_id = (SELECT d.id FROM devices d WHERE d.driver_id = `skin_temp_readings`.driver_id)
                          AND t2.recorded_at = `skin_temp_readings`.recorded_at
                      )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE `skin_temp_readings`
                    SET device_id = (SELECT d.id FROM devices d WHERE d.driver_id = `skin_temp_readings`.driver_id)
                    WHERE driver_id IS NOT NULL
                      AND device_id IS NULL
                      AND (SELECT COUNT(*) FROM devices d2 WHERE d2.driver_id = `skin_temp_readings`.driver_id) = 1
                    """.trimIndent()
                )

                db.execSQL(
                    "DELETE FROM `steps_readings` WHERE device_id IS NULL AND id NOT IN " +
                        "(SELECT MAX(id) FROM `steps_readings` WHERE device_id IS NULL GROUP BY recorded_at)"
                )
                db.execSQL(
                    """
                    DELETE FROM `steps_readings`
                    WHERE device_id IS NULL
                      AND driver_id IS NOT NULL
                      AND (SELECT COUNT(*) FROM devices d2 WHERE d2.driver_id = `steps_readings`.driver_id) = 1
                      AND EXISTS (
                        SELECT 1 FROM `steps_readings` t2
                        WHERE t2.device_id = (SELECT d.id FROM devices d WHERE d.driver_id = `steps_readings`.driver_id)
                          AND t2.recorded_at = `steps_readings`.recorded_at
                      )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE `steps_readings`
                    SET device_id = (SELECT d.id FROM devices d WHERE d.driver_id = `steps_readings`.driver_id)
                    WHERE driver_id IS NOT NULL
                      AND device_id IS NULL
                      AND (SELECT COUNT(*) FROM devices d2 WHERE d2.driver_id = `steps_readings`.driver_id) = 1
                    """.trimIndent()
                )

                db.execSQL(
                    "DELETE FROM `blood_pressure_readings` WHERE device_id IS NULL AND id NOT IN " +
                        "(SELECT MAX(id) FROM `blood_pressure_readings` WHERE device_id IS NULL GROUP BY recorded_at)"
                )
                db.execSQL(
                    """
                    DELETE FROM `blood_pressure_readings`
                    WHERE device_id IS NULL
                      AND driver_id IS NOT NULL
                      AND (SELECT COUNT(*) FROM devices d2 WHERE d2.driver_id = `blood_pressure_readings`.driver_id) = 1
                      AND EXISTS (
                        SELECT 1 FROM `blood_pressure_readings` t2
                        WHERE t2.device_id = (SELECT d.id FROM devices d WHERE d.driver_id = `blood_pressure_readings`.driver_id)
                          AND t2.recorded_at = `blood_pressure_readings`.recorded_at
                      )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE `blood_pressure_readings`
                    SET device_id = (SELECT d.id FROM devices d WHERE d.driver_id = `blood_pressure_readings`.driver_id)
                    WHERE driver_id IS NOT NULL
                      AND device_id IS NULL
                      AND (SELECT COUNT(*) FROM devices d2 WHERE d2.driver_id = `blood_pressure_readings`.driver_id) = 1
                    """.trimIndent()
                )

                db.execSQL(
                    "DELETE FROM `glucose_readings` WHERE device_id IS NULL AND id NOT IN " +
                        "(SELECT MAX(id) FROM `glucose_readings` WHERE device_id IS NULL GROUP BY recorded_at)"
                )
                db.execSQL(
                    """
                    DELETE FROM `glucose_readings`
                    WHERE device_id IS NULL
                      AND driver_id IS NOT NULL
                      AND (SELECT COUNT(*) FROM devices d2 WHERE d2.driver_id = `glucose_readings`.driver_id) = 1
                      AND EXISTS (
                        SELECT 1 FROM `glucose_readings` t2
                        WHERE t2.device_id = (SELECT d.id FROM devices d WHERE d.driver_id = `glucose_readings`.driver_id)
                          AND t2.recorded_at = `glucose_readings`.recorded_at
                      )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE `glucose_readings`
                    SET device_id = (SELECT d.id FROM devices d WHERE d.driver_id = `glucose_readings`.driver_id)
                    WHERE driver_id IS NOT NULL
                      AND device_id IS NULL
                      AND (SELECT COUNT(*) FROM devices d2 WHERE d2.driver_id = `glucose_readings`.driver_id) = 1
                    """.trimIndent()
                )

                db.execSQL(
                    "DELETE FROM `active_calorie_readings` WHERE device_id IS NULL AND id NOT IN " +
                        "(SELECT MAX(id) FROM `active_calorie_readings` WHERE device_id IS NULL GROUP BY recorded_at)"
                )
                db.execSQL(
                    """
                    DELETE FROM `active_calorie_readings`
                    WHERE device_id IS NULL
                      AND driver_id IS NOT NULL
                      AND (SELECT COUNT(*) FROM devices d2 WHERE d2.driver_id = `active_calorie_readings`.driver_id) = 1
                      AND EXISTS (
                        SELECT 1 FROM `active_calorie_readings` t2
                        WHERE t2.device_id = (SELECT d.id FROM devices d WHERE d.driver_id = `active_calorie_readings`.driver_id)
                          AND t2.recorded_at = `active_calorie_readings`.recorded_at
                      )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE `active_calorie_readings`
                    SET device_id = (SELECT d.id FROM devices d WHERE d.driver_id = `active_calorie_readings`.driver_id)
                    WHERE driver_id IS NOT NULL
                      AND device_id IS NULL
                      AND (SELECT COUNT(*) FROM devices d2 WHERE d2.driver_id = `active_calorie_readings`.driver_id) = 1
                    """.trimIndent()
                )

                db.execSQL(
                    "DELETE FROM `total_calorie_readings` WHERE device_id IS NULL AND id NOT IN " +
                        "(SELECT MAX(id) FROM `total_calorie_readings` WHERE device_id IS NULL GROUP BY recorded_at)"
                )
                db.execSQL(
                    """
                    DELETE FROM `total_calorie_readings`
                    WHERE device_id IS NULL
                      AND driver_id IS NOT NULL
                      AND (SELECT COUNT(*) FROM devices d2 WHERE d2.driver_id = `total_calorie_readings`.driver_id) = 1
                      AND EXISTS (
                        SELECT 1 FROM `total_calorie_readings` t2
                        WHERE t2.device_id = (SELECT d.id FROM devices d WHERE d.driver_id = `total_calorie_readings`.driver_id)
                          AND t2.recorded_at = `total_calorie_readings`.recorded_at
                      )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE `total_calorie_readings`
                    SET device_id = (SELECT d.id FROM devices d WHERE d.driver_id = `total_calorie_readings`.driver_id)
                    WHERE driver_id IS NOT NULL
                      AND device_id IS NULL
                      AND (SELECT COUNT(*) FROM devices d2 WHERE d2.driver_id = `total_calorie_readings`.driver_id) = 1
                    """.trimIndent()
                )
            }
        }
    }
}
