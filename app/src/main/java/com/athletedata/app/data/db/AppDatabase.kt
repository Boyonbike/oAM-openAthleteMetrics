package com.athletedata.app.data.db

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
 */
@Database(
    entities = [
        MetricReadingEntity::class,
        SleepSessionEntity::class,
        DailySummaryEntity::class,
        DailyContextEntity::class,
        QuestionDefinitionEntity::class,
        QuestionResponseEntity::class,
    ],
    version = 4,
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
    }
}
