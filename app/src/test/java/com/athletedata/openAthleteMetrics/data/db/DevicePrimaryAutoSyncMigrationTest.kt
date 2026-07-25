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
 * Exercises the real MIGRATION_23_24 SQL against the exported schema JSONs in app/schemas:
 * is_primary/auto_sync_enabled are added to devices, auto_sync_enabled defaults to 1 for all
 * existing rows via the column DEFAULT, and is_primary is backfilled onto exactly one device
 * (the most-recently-active one, same heuristic autoConnectOnStartup used before this
 * migration) with a deterministic id-ASC tie-break.
 */
@RunWith(RobolectricTestRunner::class)
class DevicePrimaryAutoSyncMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun `columns are added with correct NOT NULL defaults`() {
        helper.createDatabase(TEST_DB, 23).apply {
            execSQL(
                "INSERT INTO devices (id, ble_address, driver_id, display_name) " +
                    "VALUES (1, 'AA:BB:CC:DD:EE:01', 'hume_band_v1', 'Hume Band')"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 24, true, AppDatabase.MIGRATION_23_24)

        var isPrimaryNotNull = false
        var autoSyncNotNull = false
        migrated.query("PRAGMA table_info(`devices`)").use { cursor ->
            while (cursor.moveToNext()) {
                // PRAGMA table_info columns: cid(0), name(1), type(2), notnull(3), dflt_value(4), pk(5)
                when (cursor.getString(1)) {
                    "is_primary" -> isPrimaryNotNull = cursor.getInt(3) == 1
                    "auto_sync_enabled" -> autoSyncNotNull = cursor.getInt(3) == 1
                }
            }
        }
        assertTrue("is_primary should be NOT NULL", isPrimaryNotNull)
        assertTrue("auto_sync_enabled should be NOT NULL", autoSyncNotNull)

        migrated.query("SELECT auto_sync_enabled FROM devices WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
    }

    @Test
    fun `single device with last_sync_ms becomes primary`() {
        helper.createDatabase(TEST_DB, 23).apply {
            execSQL(
                "INSERT INTO devices (id, ble_address, driver_id, display_name, last_sync_ms) " +
                    "VALUES (10, 'AA:BB:CC:DD:EE:01', 'hume_band_v1', 'Hume Band', 1000)"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 24, true, AppDatabase.MIGRATION_23_24)

        migrated.query("SELECT is_primary FROM devices WHERE id = 10").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
    }

    @Test
    fun `most recently active of several devices becomes primary`() {
        helper.createDatabase(TEST_DB, 23).apply {
            execSQL(
                "INSERT INTO devices (id, ble_address, driver_id, display_name, last_sync_ms, last_seen_ms) " +
                    "VALUES (20, 'AA:BB:CC:DD:EE:02', 'driver_a', 'Dev A', 5000, 5000)"
            )
            execSQL(
                "INSERT INTO devices (id, ble_address, driver_id, display_name, last_sync_ms, last_seen_ms) " +
                    "VALUES (21, 'AA:BB:CC:DD:EE:03', 'driver_b', 'Dev B', NULL, 9000)"
            )
            execSQL(
                "INSERT INTO devices (id, ble_address, driver_id, display_name, last_sync_ms, last_seen_ms) " +
                    "VALUES (22, 'AA:BB:CC:DD:EE:04', 'driver_c', 'Dev C', 3000, 4000)"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 24, true, AppDatabase.MIGRATION_23_24)

        migrated.query("SELECT id FROM devices WHERE is_primary = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(21L, cursor.getLong(0))
            assertTrue("exactly one primary expected", !cursor.moveToNext())
        }
    }

    @Test
    fun `all null timestamps tie break to lowest id`() {
        helper.createDatabase(TEST_DB, 23).apply {
            execSQL(
                "INSERT INTO devices (id, ble_address, driver_id, display_name) " +
                    "VALUES (31, 'AA:BB:CC:DD:EE:05', 'driver_a', 'Dev A')"
            )
            execSQL(
                "INSERT INTO devices (id, ble_address, driver_id, display_name) " +
                    "VALUES (30, 'AA:BB:CC:DD:EE:06', 'driver_b', 'Dev B')"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 24, true, AppDatabase.MIGRATION_23_24)

        migrated.query("SELECT id FROM devices WHERE is_primary = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(30L, cursor.getLong(0))
            assertTrue("exactly one primary expected", !cursor.moveToNext())
        }
    }

    @Test
    fun `exact timestamp tie breaks to lowest id`() {
        helper.createDatabase(TEST_DB, 23).apply {
            execSQL(
                "INSERT INTO devices (id, ble_address, driver_id, display_name, last_sync_ms) " +
                    "VALUES (41, 'AA:BB:CC:DD:EE:07', 'driver_a', 'Dev A', 7000)"
            )
            execSQL(
                "INSERT INTO devices (id, ble_address, driver_id, display_name, last_sync_ms) " +
                    "VALUES (40, 'AA:BB:CC:DD:EE:08', 'driver_b', 'Dev B', 7000)"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 24, true, AppDatabase.MIGRATION_23_24)

        migrated.query("SELECT id FROM devices WHERE is_primary = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(40L, cursor.getLong(0))
            assertTrue("exactly one primary expected", !cursor.moveToNext())
        }
    }

    @Test
    fun `zero devices migrates without crash`() {
        helper.createDatabase(TEST_DB, 23).close()

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 24, true, AppDatabase.MIGRATION_23_24)

        migrated.query("SELECT COUNT(*) FROM devices WHERE is_primary = 1").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun `all devices default to auto_sync_enabled 1 after migration`() {
        helper.createDatabase(TEST_DB, 23).apply {
            execSQL(
                "INSERT INTO devices (id, ble_address, driver_id, display_name, last_sync_ms) " +
                    "VALUES (50, 'AA:BB:CC:DD:EE:09', 'driver_a', 'Dev A', 1000)"
            )
            execSQL(
                "INSERT INTO devices (id, ble_address, driver_id, display_name) " +
                    "VALUES (51, 'AA:BB:CC:DD:EE:10', 'driver_b', 'Dev B')"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 24, true, AppDatabase.MIGRATION_23_24)

        migrated.query("SELECT COUNT(*) FROM devices WHERE auto_sync_enabled != 1").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun `at most one primary device after migration`() {
        helper.createDatabase(TEST_DB, 23).apply {
            execSQL(
                "INSERT INTO devices (id, ble_address, driver_id, display_name, last_sync_ms) " +
                    "VALUES (60, 'AA:BB:CC:DD:EE:11', 'driver_a', 'Dev A', 1000)"
            )
            execSQL(
                "INSERT INTO devices (id, ble_address, driver_id, display_name, last_sync_ms) " +
                    "VALUES (61, 'AA:BB:CC:DD:EE:12', 'driver_b', 'Dev B', 2000)"
            )
            execSQL(
                "INSERT INTO devices (id, ble_address, driver_id, display_name, last_sync_ms) " +
                    "VALUES (62, 'AA:BB:CC:DD:EE:13', 'driver_c', 'Dev C', 3000)"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 24, true, AppDatabase.MIGRATION_23_24)

        migrated.query("SELECT COUNT(*) FROM devices WHERE is_primary = 1").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
    }

    private companion object {
        const val TEST_DB = "device-primary-autosync-migration-test"
    }
}
