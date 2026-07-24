package com.athletedata.openAthleteMetrics.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises the real MIGRATION_21_22 SQL against the exported schema JSONs in app/schemas:
 * device_id is added to 13 tables and conditionally backfilled from devices.id only when
 * driver_id maps to exactly one device row -- mirrors [BaselineRangeRemovalMigrationTest]'s
 * style. Also proves the pre-existing driver_id-based unique/dedup indexes are unchanged.
 */
@RunWith(RobolectricTestRunner::class)
class DeviceIdBackfillMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun `single device backfills device_id on hr_readings`() {
        helper.createDatabase(TEST_DB, 21).apply {
            execSQL(
                "INSERT INTO devices (id, ble_address, driver_id, display_name) " +
                    "VALUES (10, 'AA:BB:CC:DD:EE:01', 'hume_band_v1', 'Hume Band')"
            )
            execSQL(
                "INSERT INTO hr_readings (recorded_at, created_at, source, driver_id, bpm) " +
                    "VALUES (1000, 1000, 'DEVICE', 'hume_band_v1', 60)"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 22, true, AppDatabase.MIGRATION_21_22)

        migrated.query("SELECT device_id FROM hr_readings").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(10L, cursor.getLong(0))
        }
    }

    @Test
    fun `two devices sharing driver_id leaves device_id NULL`() {
        helper.createDatabase(TEST_DB, 21).apply {
            execSQL(
                "INSERT INTO devices (id, ble_address, driver_id, display_name) " +
                    "VALUES (20, 'AA:BB:CC:DD:EE:02', 'shared_driver', 'Dev A')"
            )
            execSQL(
                "INSERT INTO devices (id, ble_address, driver_id, display_name) " +
                    "VALUES (21, 'AA:BB:CC:DD:EE:03', 'shared_driver', 'Dev B')"
            )
            execSQL(
                "INSERT INTO hr_readings (recorded_at, created_at, source, driver_id, bpm) " +
                    "VALUES (2000, 2000, 'DEVICE', 'shared_driver', 65)"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 22, true, AppDatabase.MIGRATION_21_22)

        migrated.query("SELECT device_id FROM hr_readings").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue("device_id should be NULL for an ambiguous shared driver_id", cursor.isNull(0))
        }
    }

    @Test
    fun `orphaned driver_id leaves device_id NULL`() {
        helper.createDatabase(TEST_DB, 21).apply {
            execSQL(
                "INSERT INTO hr_readings (recorded_at, created_at, source, driver_id, bpm) " +
                    "VALUES (3000, 3000, 'DEVICE', 'unknown_driver', 70)"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 22, true, AppDatabase.MIGRATION_21_22)

        migrated.query("SELECT device_id FROM hr_readings").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue("device_id should be NULL for an orphaned driver_id", cursor.isNull(0))
        }
    }

    @Test
    fun `null driver_id leaves device_id NULL`() {
        helper.createDatabase(TEST_DB, 21).apply {
            execSQL(
                "INSERT INTO hr_readings (recorded_at, created_at, source, driver_id, bpm) " +
                    "VALUES (4000, 4000, 'SEEDER', NULL, 55)"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 22, true, AppDatabase.MIGRATION_21_22)

        migrated.query("SELECT device_id FROM hr_readings").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue("device_id should stay NULL for a null driver_id (seeder-style row)", cursor.isNull(0))
        }
    }

    @Test
    fun `migration causes no row loss across mixed backfill scenarios`() {
        helper.createDatabase(TEST_DB, 21).apply {
            execSQL(
                "INSERT INTO devices (id, ble_address, driver_id, display_name) " +
                    "VALUES (30, 'AA:BB:CC:DD:EE:04', 'solo_driver', 'Solo')"
            )
            execSQL(
                "INSERT INTO devices (id, ble_address, driver_id, display_name) " +
                    "VALUES (31, 'AA:BB:CC:DD:EE:05', 'shared_driver_2', 'Dev A')"
            )
            execSQL(
                "INSERT INTO devices (id, ble_address, driver_id, display_name) " +
                    "VALUES (32, 'AA:BB:CC:DD:EE:06', 'shared_driver_2', 'Dev B')"
            )
            execSQL(
                "INSERT INTO hr_readings (recorded_at, created_at, source, driver_id, bpm) " +
                    "VALUES (5000, 5000, 'DEVICE', 'solo_driver', 60)"
            )
            execSQL(
                "INSERT INTO hr_readings (recorded_at, created_at, source, driver_id, bpm) " +
                    "VALUES (5001, 5001, 'DEVICE', 'shared_driver_2', 61)"
            )
            execSQL(
                "INSERT INTO hr_readings (recorded_at, created_at, source, driver_id, bpm) " +
                    "VALUES (5002, 5002, 'DEVICE', 'no_such_driver', 62)"
            )
            execSQL(
                "INSERT INTO hr_readings (recorded_at, created_at, source, driver_id, bpm) " +
                    "VALUES (5003, 5003, 'SEEDER', NULL, 63)"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 22, true, AppDatabase.MIGRATION_21_22)

        migrated.query("SELECT COUNT(*) FROM hr_readings").use { cursor ->
            cursor.moveToFirst()
            assertEquals(4, cursor.getInt(0))
        }
    }

    @Test
    fun `device_id column exists on all 13 tables after migration`() {
        helper.createDatabase(TEST_DB, 21).close()

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 22, true, AppDatabase.MIGRATION_21_22)

        val tables = listOf(
            "hr_readings", "hrv_readings", "spo2_readings", "respiration_readings",
            "skin_temp_readings", "steps_readings", "blood_pressure_readings",
            "glucose_readings", "active_calorie_readings", "total_calorie_readings",
            "metric_readings_staging", "sleep_sessions", "activities",
        )
        for (table in tables) {
            migrated.query("PRAGMA table_info(`$table`)").use { cursor ->
                var found = false
                while (cursor.moveToNext()) {
                    // PRAGMA table_info columns: cid(0), name(1), type(2), notnull(3), dflt_value(4), pk(5)
                    if (cursor.getString(1) == "device_id") {
                        found = true
                        break
                    }
                }
                assertTrue("device_id column missing on $table", found)
            }
        }
    }

    @Test
    fun `single device backfills device_id on sleep_sessions and activities`() {
        helper.createDatabase(TEST_DB, 21).apply {
            execSQL(
                "INSERT INTO devices (id, ble_address, driver_id, display_name) " +
                    "VALUES (40, 'AA:BB:CC:DD:EE:07', 'hume_band_v1', 'Hume Band')"
            )
            execSQL(
                "INSERT INTO sleep_sessions (date, sleep_start_ms, sleep_end_ms, duration_minutes, source, driver_id) " +
                    "VALUES ('2026-01-01', 1000, 2000, 10, 'DEVICE', 'hume_band_v1')"
            )
            execSQL(
                "INSERT INTO activities (start_time, end_time, duration_minutes, device_name, source, driver_id) " +
                    "VALUES (1000, 2000, 10, 'Outdoor Run', 'DEVICE', 'hume_band_v1')"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 22, true, AppDatabase.MIGRATION_21_22)

        migrated.query("SELECT device_id FROM sleep_sessions").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(40L, cursor.getLong(0))
        }
        migrated.query("SELECT device_id FROM activities").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(40L, cursor.getLong(0))
        }
    }

    @Test
    fun `duplicate driver_id and recorded_at still collapse to one row post-migration`() {
        helper.createDatabase(TEST_DB, 21).close()
        val migrated = helper.runMigrationsAndValidate(TEST_DB, 22, true, AppDatabase.MIGRATION_21_22)

        // Mirrors HrReadingDao.insert()'s real @Insert(onConflict = REPLACE) strategy against
        // the still-present (driver_id, recorded_at) unique index -- proves session 1 changed
        // no dedup behavior, even though the two rows here carry different device_id values.
        migrated.execSQL(
            "INSERT OR REPLACE INTO hr_readings (recorded_at, created_at, source, driver_id, device_id, bpm) " +
                "VALUES (9000, 9000, 'DEVICE', 'dup_driver', 1, 60)"
        )
        migrated.execSQL(
            "INSERT OR REPLACE INTO hr_readings (recorded_at, created_at, source, driver_id, device_id, bpm) " +
                "VALUES (9000, 9000, 'DEVICE', 'dup_driver', 2, 61)"
        )

        migrated.query("SELECT COUNT(*) FROM hr_readings WHERE driver_id = 'dup_driver'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
    }

    private companion object {
        const val TEST_DB = "device-id-backfill-migration-test"
    }
}
