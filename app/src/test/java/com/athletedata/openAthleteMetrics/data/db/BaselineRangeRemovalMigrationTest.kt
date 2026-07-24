package com.athletedata.openAthleteMetrics.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises the real MIGRATION_20_21 SQL against the exported schema JSONs in app/schemas,
 * confirming `baseline_range` drops cleanly and everything else survives -- mirrors
 * [MetricDailyStatsMigrationTest]'s style.
 */
@RunWith(RobolectricTestRunner::class)
class BaselineRangeRemovalMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun `MIGRATION_20_21 drops baseline_range and preserves metric_daily_stats`() {
        helper.createDatabase(TEST_DB, 20).apply {
            execSQL(
                """
                INSERT INTO baseline_range
                    (metric_type, mean, std_dev, lower, upper, window_days, calculated_at)
                VALUES ('HR', 60.0, 5.0, 55.0, 65.0, 30, 0)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO metric_daily_stats
                    (summary_date, metric_type, mean, std_dev, mean_pct, sample_count, window_days, minimum_days, config_hash, computed_at)
                VALUES ('2026-01-01', 'HR', 60.0, 5.0, NULL, 10, 30, 14, 'hash', 0)
                """.trimIndent()
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 21, true, AppDatabase.MIGRATION_20_21)

        migrated.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'baseline_range'",
        ).use { cursor ->
            assertFalse("baseline_range should no longer exist", cursor.moveToFirst())
        }

        migrated.query("SELECT COUNT(*) FROM metric_daily_stats").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
    }

    @Test
    fun `full chain 16 through 21 creates then drops baseline_range cleanly`() {
        helper.createDatabase(TEST_DB, 16).close()

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB, 21, true,
            AppDatabase.MIGRATION_16_17,
            AppDatabase.MIGRATION_17_18,
            AppDatabase.MIGRATION_18_19,
            AppDatabase.MIGRATION_19_20,
            AppDatabase.MIGRATION_20_21,
        )

        migrated.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'baseline_range'",
        ).use { cursor ->
            assertFalse("baseline_range should no longer exist", cursor.moveToFirst())
        }

        migrated.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'metric_daily_stats'",
        ).use { cursor ->
            assertTrue("metric_daily_stats should exist", cursor.moveToFirst())
        }
    }

    @Test
    fun `full chain 16 through 21 preserves pre-existing data at each surviving table`() {
        helper.createDatabase(TEST_DB, 16).apply {
            execSQL(
                "INSERT INTO daily_summary (date, source, computed_at) VALUES ('2026-01-01', 'DEVICE', 0)",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 17, true, AppDatabase.MIGRATION_16_17).apply {
            execSQL(
                """
                INSERT INTO baseline_range
                    (metric_type, mean, std_dev, lower, upper, window_days, calculated_at)
                VALUES ('HR', 60.0, 5.0, 55.0, 65.0, 30, 0)
                """.trimIndent()
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DB, 20, true,
            AppDatabase.MIGRATION_17_18,
            AppDatabase.MIGRATION_18_19,
            AppDatabase.MIGRATION_19_20,
        ).apply {
            execSQL(
                """
                INSERT INTO metric_daily_stats
                    (summary_date, metric_type, mean, std_dev, mean_pct, sample_count, window_days, minimum_days, config_hash, computed_at)
                VALUES ('2026-01-01', 'HR', 60.0, 5.0, NULL, 10, 30, 14, 'hash', 0)
                """.trimIndent()
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 21, true, AppDatabase.MIGRATION_20_21)

        migrated.query("SELECT date FROM daily_summary").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("2026-01-01", cursor.getString(0))
        }

        migrated.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'baseline_range'",
        ).use { cursor ->
            assertFalse("baseline_range should no longer exist", cursor.moveToFirst())
        }

        migrated.query("SELECT metric_type, mean FROM metric_daily_stats").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("HR", cursor.getString(0))
            assertEquals(60.0, cursor.getDouble(1), 0.0)
        }
    }

    private companion object {
        const val TEST_DB = "baseline-range-removal-migration-test"
    }
}
