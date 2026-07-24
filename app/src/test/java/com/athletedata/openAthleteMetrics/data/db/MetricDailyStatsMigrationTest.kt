package com.athletedata.openAthleteMetrics.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises the real MIGRATION_19_20 SQL (Room.inMemoryDatabaseBuilder alone can't: it only ever
 * builds the latest schema from entity annotations, never runs a migration's execSQL) against the
 * exported schema JSONs in app/schemas, confirming the upgrade path is clean and additive.
 */
@RunWith(RobolectricTestRunner::class)
class MetricDailyStatsMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun `MIGRATION_19_20 preserves existing rows and adds a queryable metric_daily_stats table`() {
        helper.createDatabase(TEST_DB, 19).apply {
            execSQL(
                "INSERT INTO daily_summary (date, source, computed_at) VALUES ('2026-01-01', 'DEVICE', 0)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 20, true, AppDatabase.MIGRATION_19_20)

        migrated.query("SELECT date FROM daily_summary").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("2026-01-01", cursor.getString(0))
        }

        migrated.query("SELECT COUNT(*) FROM metric_daily_stats").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
    }

    private companion object {
        const val TEST_DB = "metric-daily-stats-migration-test"
    }
}
