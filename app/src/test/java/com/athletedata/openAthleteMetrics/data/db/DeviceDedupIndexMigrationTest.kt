package com.athletedata.openAthleteMetrics.data.db

import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises the real MIGRATION_22_23 SQL against the exported schema JSONs in app/schemas:
 * the driver_id-based UNIQUE dedup indexes on the 13 device_id-bearing tables are replaced
 * with device_id-based ones, fixing two physical units sharing a driver colliding at the
 * same timestamp. Proves no table is rebuilt, no row is lost, and the old index is gone.
 */
@RunWith(RobolectricTestRunner::class)
class DeviceDedupIndexMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    private data class IndexSwap(val table: String, val oldIndex: String, val newIndex: String)

    private val swaps = listOf(
        IndexSwap("hr_readings", "index_hr_readings_driver_id_recorded_at", "index_hr_readings_device_id_recorded_at"),
        IndexSwap("hrv_readings", "index_hrv_readings_driver_id_recorded_at", "index_hrv_readings_device_id_recorded_at"),
        IndexSwap("spo2_readings", "index_spo2_readings_driver_id_recorded_at", "index_spo2_readings_device_id_recorded_at"),
        IndexSwap("respiration_readings", "index_respiration_readings_driver_id_recorded_at", "index_respiration_readings_device_id_recorded_at"),
        IndexSwap("skin_temp_readings", "index_skin_temp_readings_driver_id_recorded_at", "index_skin_temp_readings_device_id_recorded_at"),
        IndexSwap("steps_readings", "index_steps_readings_driver_id_recorded_at", "index_steps_readings_device_id_recorded_at"),
        IndexSwap("blood_pressure_readings", "index_blood_pressure_readings_driver_id_recorded_at", "index_blood_pressure_readings_device_id_recorded_at"),
        IndexSwap("glucose_readings", "index_glucose_readings_driver_id_recorded_at", "index_glucose_readings_device_id_recorded_at"),
        IndexSwap("active_calorie_readings", "index_active_calorie_readings_driver_id_recorded_at", "index_active_calorie_readings_device_id_recorded_at"),
        IndexSwap("total_calorie_readings", "index_total_calorie_readings_driver_id_recorded_at", "index_total_calorie_readings_device_id_recorded_at"),
        IndexSwap("metric_readings_staging", "index_metric_readings_staging_driver_id_metric_type_recorded_at", "index_metric_readings_staging_device_id_metric_type_recorded_at"),
        IndexSwap("sleep_sessions", "index_sleep_sessions_driver_id_date", "index_sleep_sessions_device_id_date"),
        IndexSwap("activities", "index_activities_driver_id_start_time", "index_activities_device_id_start_time"),
    )

    private fun indexNames(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String): Map<String, Boolean> {
        val result = mutableMapOf<String, Boolean>()
        db.query("PRAGMA index_list(`$table`)").use { cursor ->
            val nameCol = cursor.getColumnIndexOrThrow("name")
            val uniqueCol = cursor.getColumnIndexOrThrow("unique")
            while (cursor.moveToNext()) {
                result[cursor.getString(nameCol)] = cursor.getInt(uniqueCol) == 1
            }
        }
        return result
    }

    @Test
    fun `new device-based unique indexes replace driver-based ones on all 13 tables`() {
        helper.createDatabase(TEST_DB, 22).close()
        val migrated = helper.runMigrationsAndValidate(TEST_DB, 23, true, AppDatabase.MIGRATION_22_23)

        for (swap in swaps) {
            val indices = indexNames(migrated, swap.table)
            assertFalse("${swap.oldIndex} should be gone on ${swap.table}", indices.containsKey(swap.oldIndex))
            assertTrue("${swap.newIndex} should exist on ${swap.table}", indices.containsKey(swap.newIndex))
            assertTrue("${swap.newIndex} should be unique", indices[swap.newIndex] == true)
        }

        // Non-unique index on metric_readings_staging is untouched.
        val stagingIndices = indexNames(migrated, "metric_readings_staging")
        assertTrue(stagingIndices.containsKey("index_metric_readings_staging_metric_type_recorded_at"))
        assertEquals(false, stagingIndices["index_metric_readings_staging_metric_type_recorded_at"])
    }

    @Test
    fun `cross-device pair on hr_readings coexists after migration`() {
        // A "would-be cross-device pair" (same driver_id + recorded_at, different device_id)
        // cannot be seeded at v22 -- the old (driver_id, recorded_at) unique index rejects it
        // outright (see the next test). So we seed one row pre-migration, migrate, then insert
        // the second row directly against the migrated (v23) database: exactly the insert that
        // would have thrown at v22, now succeeding because the index no longer covers driver_id.
        helper.createDatabase(TEST_DB, 22).apply {
            execSQL(
                "INSERT INTO hr_readings (recorded_at, created_at, source, driver_id, device_id, bpm) " +
                    "VALUES (9000, 9000, 'DEVICE', 'x', 101, 60)"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 23, true, AppDatabase.MIGRATION_22_23)
        migrated.execSQL(
            "INSERT INTO hr_readings (recorded_at, created_at, source, driver_id, device_id, bpm) " +
                "VALUES (9000, 9000, 'DEVICE', 'x', 102, 61)"
        )

        migrated.query("SELECT COUNT(*) FROM hr_readings WHERE driver_id = 'x'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(2, cursor.getInt(0))
        }
        val deviceIds = mutableSetOf<Long>()
        migrated.query("SELECT device_id FROM hr_readings WHERE driver_id = 'x' ORDER BY device_id").use { cursor ->
            while (cursor.moveToNext()) deviceIds.add(cursor.getLong(0))
        }
        assertEquals(setOf(101L, 102L), deviceIds)
    }

    @Test
    fun `cross-device pair cannot be seeded at v22`() {
        helper.createDatabase(TEST_DB, 22).apply {
            execSQL(
                "INSERT INTO hr_readings (recorded_at, created_at, source, driver_id, device_id, bpm) " +
                    "VALUES (9500, 9500, 'DEVICE', 'y', 201, 60)"
            )
            try {
                execSQL(
                    "INSERT INTO hr_readings (recorded_at, created_at, source, driver_id, device_id, bpm) " +
                        "VALUES (9500, 9500, 'DEVICE', 'y', 202, 61)"
                )
                fail("expected SQLiteConstraintException: v22's (driver_id, recorded_at) unique index should reject this")
            } catch (e: SQLiteConstraintException) {
                // expected -- this is the exact collision bug the migration fixes.
            }
            close()
        }
    }

    @Test
    fun `same-device dedup still collapses on hr_readings post-migration`() {
        helper.createDatabase(TEST_DB, 22).close()
        val migrated = helper.runMigrationsAndValidate(TEST_DB, 23, true, AppDatabase.MIGRATION_22_23)

        migrated.execSQL(
            "INSERT OR REPLACE INTO hr_readings (recorded_at, created_at, source, driver_id, device_id, bpm) " +
                "VALUES (10000, 10000, 'DEVICE', 'z', 5, 60)"
        )
        migrated.execSQL(
            "INSERT OR REPLACE INTO hr_readings (recorded_at, created_at, source, driver_id, device_id, bpm) " +
                "VALUES (10000, 10000, 'DEVICE', 'z', 5, 65)"
        )

        migrated.query("SELECT COUNT(*), MAX(bpm) FROM hr_readings WHERE device_id = 5").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
            assertEquals(65, cursor.getInt(1))
        }
    }

    @Test
    fun `force-replace reprocessing over backfilled single-device data does not duplicate rows`() {
        helper.createDatabase(TEST_DB, 22).close()
        val migrated = helper.runMigrationsAndValidate(TEST_DB, 23, true, AppDatabase.MIGRATION_22_23)

        val insertStatements = (0 until 3).map { i ->
            "INSERT OR REPLACE INTO hr_readings (recorded_at, created_at, source, driver_id, device_id, bpm) " +
                "VALUES (${11000 + i}, ${11000 + i}, 'DEVICE', 'reproc', 9, ${60 + i})"
        }
        insertStatements.forEach { migrated.execSQL(it) }
        // Simulate an idempotent re-sync pass replaying the same rows unchanged.
        insertStatements.forEach { migrated.execSQL(it) }

        migrated.query("SELECT COUNT(*) FROM hr_readings WHERE device_id = 9").use { cursor ->
            cursor.moveToFirst()
            assertEquals(3, cursor.getInt(0))
        }
    }

    @Test
    fun `cross-device pair on sleep_sessions coexists after migration`() {
        helper.createDatabase(TEST_DB, 22).apply {
            execSQL(
                "INSERT INTO sleep_sessions (date, sleep_start_ms, sleep_end_ms, duration_minutes, source, driver_id, device_id) " +
                    "VALUES ('2026-02-10', 1000, 2000, 10, 'DEVICE', 'sd', 301)"
            )
            close()
        }
        val migrated = helper.runMigrationsAndValidate(TEST_DB, 23, true, AppDatabase.MIGRATION_22_23)
        migrated.execSQL(
            "INSERT INTO sleep_sessions (date, sleep_start_ms, sleep_end_ms, duration_minutes, source, driver_id, device_id) " +
                "VALUES ('2026-02-10', 1000, 2000, 10, 'DEVICE', 'sd', 302)"
        )

        migrated.query("SELECT COUNT(*) FROM sleep_sessions WHERE driver_id = 'sd'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(2, cursor.getInt(0))
        }
    }

    @Test
    fun `same-device dedup still collapses on sleep_sessions post-migration`() {
        helper.createDatabase(TEST_DB, 22).close()
        val migrated = helper.runMigrationsAndValidate(TEST_DB, 23, true, AppDatabase.MIGRATION_22_23)

        migrated.execSQL(
            "INSERT OR REPLACE INTO sleep_sessions (date, sleep_start_ms, sleep_end_ms, duration_minutes, source, driver_id, device_id) " +
                "VALUES ('2026-02-11', 1000, 2000, 10, 'DEVICE', 'sd2', 6)"
        )
        migrated.execSQL(
            "INSERT OR REPLACE INTO sleep_sessions (date, sleep_start_ms, sleep_end_ms, duration_minutes, source, driver_id, device_id) " +
                "VALUES ('2026-02-11', 1000, 2000, 15, 'DEVICE', 'sd2', 6)"
        )

        migrated.query("SELECT COUNT(*), MAX(duration_minutes) FROM sleep_sessions WHERE device_id = 6").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
            assertEquals(15, cursor.getInt(1))
        }
    }

    @Test
    fun `cross-device pair on activities coexists after migration`() {
        helper.createDatabase(TEST_DB, 22).apply {
            execSQL(
                "INSERT INTO activities (start_time, end_time, duration_minutes, device_name, source, driver_id, device_id) " +
                    "VALUES (5000, 6000, 10, 'Band A', 'DEVICE', 'ad', 401)"
            )
            close()
        }
        val migrated = helper.runMigrationsAndValidate(TEST_DB, 23, true, AppDatabase.MIGRATION_22_23)
        migrated.execSQL(
            "INSERT INTO activities (start_time, end_time, duration_minutes, device_name, source, driver_id, device_id) " +
                "VALUES (5000, 6000, 10, 'Band B', 'DEVICE', 'ad', 402)"
        )

        migrated.query("SELECT COUNT(*) FROM activities WHERE driver_id = 'ad'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(2, cursor.getInt(0))
        }
    }

    @Test
    fun `same-device dedup still collapses on activities post-migration`() {
        helper.createDatabase(TEST_DB, 22).close()
        val migrated = helper.runMigrationsAndValidate(TEST_DB, 23, true, AppDatabase.MIGRATION_22_23)

        migrated.execSQL(
            "INSERT OR REPLACE INTO activities (start_time, end_time, duration_minutes, device_name, source, driver_id, device_id) " +
                "VALUES (7000, 8000, 10, 'Band A', 'DEVICE', 'ad2', 8)"
        )
        migrated.execSQL(
            "INSERT OR REPLACE INTO activities (start_time, end_time, duration_minutes, device_name, source, driver_id, device_id) " +
                "VALUES (7000, 8000, 20, 'Band A', 'DEVICE', 'ad2', 8)"
        )

        migrated.query("SELECT COUNT(*), MAX(duration_minutes) FROM activities WHERE device_id = 8").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
            assertEquals(20, cursor.getInt(1))
        }
    }

    @Test
    fun `migration causes no row loss across mixed device_id and NULL data`() {
        helper.createDatabase(TEST_DB, 22).apply {
            execSQL(
                "INSERT INTO hr_readings (recorded_at, created_at, source, driver_id, device_id, bpm) " +
                    "VALUES (12000, 12000, 'DEVICE', 'mix', 50, 60)"
            )
            execSQL(
                "INSERT INTO hr_readings (recorded_at, created_at, source, driver_id, device_id, bpm) " +
                    "VALUES (12001, 12001, 'SEEDER', NULL, NULL, 61)"
            )
            execSQL(
                "INSERT INTO sleep_sessions (date, sleep_start_ms, sleep_end_ms, duration_minutes, source, driver_id, device_id) " +
                    "VALUES ('2026-02-12', 1000, 2000, 10, 'DEVICE', 'mix', 50)"
            )
            execSQL(
                "INSERT INTO activities (start_time, end_time, duration_minutes, device_name, source, driver_id, device_id) " +
                    "VALUES (13000, 14000, 10, 'Band', 'DEVICE', 'mix', 50)"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 23, true, AppDatabase.MIGRATION_22_23)

        migrated.query("SELECT COUNT(*) FROM hr_readings").use { cursor ->
            cursor.moveToFirst()
            assertEquals(2, cursor.getInt(0))
        }
        migrated.query("SELECT COUNT(*) FROM sleep_sessions").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        migrated.query("SELECT COUNT(*) FROM activities").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
    }

    @Test
    fun `full chain 21 to 23 backfills device_id then migrates to device-based unique index`() {
        helper.createDatabase(TEST_DB, 21).apply {
            execSQL(
                "INSERT INTO devices (id, ble_address, driver_id, display_name) " +
                    "VALUES (60, 'AA:BB:CC:DD:EE:08', 'chain_driver', 'Chain Band')"
            )
            execSQL(
                "INSERT INTO hr_readings (recorded_at, created_at, source, driver_id, bpm) " +
                    "VALUES (15000, 15000, 'DEVICE', 'chain_driver', 60)"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB, 23, true,
            AppDatabase.MIGRATION_21_22, AppDatabase.MIGRATION_22_23,
        )

        migrated.query("SELECT device_id FROM hr_readings WHERE driver_id = 'chain_driver'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(60L, cursor.getLong(0))
        }
        val indices = indexNames(migrated, "hr_readings")
        assertTrue(indices.containsKey("index_hr_readings_device_id_recorded_at"))
        assertFalse(indices.containsKey("index_hr_readings_driver_id_recorded_at"))
    }

    private companion object {
        const val TEST_DB = "device-dedup-index-migration-test"
    }
}
