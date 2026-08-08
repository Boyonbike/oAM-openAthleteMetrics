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
 * Exercises the real MIGRATION_25_26 SQL against the exported v25 schema JSON: cleans up
 * historical duplicate device_id IS NULL rows (left behind by the now-fixed EOS sync bug
 * where device_id was never set before insert) and backfills device_id on the survivors
 * using the same single-device-per-driver_id heuristic as MIGRATION_21_22. Mirrors
 * [DeviceIdBackfillMigrationTest]'s style.
 */
@RunWith(RobolectricTestRunner::class)
class Migration25to26Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    private fun insertDevice(db: androidx.sqlite.db.SupportSQLiteDatabase, id: Long, address: String, driverId: String) {
        db.execSQL(
            "INSERT INTO devices (id, ble_address, driver_id, display_name, is_primary, auto_sync_enabled, cdm_associated) " +
                "VALUES ($id, '$address', '$driverId', 'Hume Band', 0, 1, 0)"
        )
    }

    private fun insertHrv(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        id: Long,
        recordedAt: Long,
        driverId: String?,
        deviceId: Long?,
        rmssdMs: Double,
    ) {
        db.execSQL(
            "INSERT INTO hrv_readings (id, recorded_at, created_at, source, driver_id, device_id, rmssd_ms, computed_by_version) " +
                "VALUES ($id, $recordedAt, $recordedAt, 'DEVICE', ${driverId?.let { "'$it'" } ?: "NULL"}, " +
                "${deviceId ?: "NULL"}, $rmssdMs, 1)"
        )
    }

    @Test
    fun `duplicate NULL-device rows for same recorded_at collapse to the highest id`() {
        helper.createDatabase(TEST_DB, 25).apply {
            insertHrv(this, id = 1, recordedAt = 1000, driverId = "no_such_driver", deviceId = null, rmssdMs = 40.0)
            insertHrv(this, id = 2, recordedAt = 1000, driverId = "no_such_driver", deviceId = null, rmssdMs = 40.0)
            insertHrv(this, id = 3, recordedAt = 1000, driverId = "no_such_driver", deviceId = null, rmssdMs = 40.0)
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 26, true, AppDatabase.MIGRATION_25_26)

        migrated.query("SELECT id FROM hrv_readings WHERE recorded_at = 1000").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals(3L, cursor.getLong(0))
        }
    }

    @Test
    fun `NULL-device row duplicating an already-attributed row is deleted without collision`() {
        helper.createDatabase(TEST_DB, 25).apply {
            insertDevice(this, id = 10, address = "AA:BB:CC:DD:EE:01", driverId = "hume_band_v1")
            // Correctly attributed row (e.g. written via triggerSync()).
            insertHrv(this, id = 1, recordedAt = 2000, driverId = "hume_band_v1", deviceId = 10, rmssdMs = 45.0)
            // Buggy EOS-path duplicate of the same physical reading, device_id never set.
            insertHrv(this, id = 2, recordedAt = 2000, driverId = "hume_band_v1", deviceId = null, rmssdMs = 45.0)
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 26, true, AppDatabase.MIGRATION_25_26)

        migrated.query("SELECT id, device_id FROM hrv_readings WHERE recorded_at = 2000").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
            assertEquals(10L, cursor.getLong(1))
        }
    }

    @Test
    fun `NULL-device row with unambiguous driver_id is backfilled`() {
        helper.createDatabase(TEST_DB, 25).apply {
            insertDevice(this, id = 20, address = "AA:BB:CC:DD:EE:02", driverId = "solo_driver")
            insertHrv(this, id = 1, recordedAt = 3000, driverId = "solo_driver", deviceId = null, rmssdMs = 50.0)
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 26, true, AppDatabase.MIGRATION_25_26)

        migrated.query("SELECT device_id FROM hrv_readings WHERE recorded_at = 3000").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(20L, cursor.getLong(0))
        }
    }

    @Test
    fun `ambiguous driver_id stays NULL but its duplicates still collapse`() {
        helper.createDatabase(TEST_DB, 25).apply {
            insertDevice(this, id = 30, address = "AA:BB:CC:DD:EE:03", driverId = "shared_driver")
            insertDevice(this, id = 31, address = "AA:BB:CC:DD:EE:04", driverId = "shared_driver")
            insertHrv(this, id = 1, recordedAt = 4000, driverId = "shared_driver", deviceId = null, rmssdMs = 55.0)
            insertHrv(this, id = 2, recordedAt = 4000, driverId = "shared_driver", deviceId = null, rmssdMs = 55.0)
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 26, true, AppDatabase.MIGRATION_25_26)

        migrated.query("SELECT id, device_id FROM hrv_readings WHERE recorded_at = 4000").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals(2L, cursor.getLong(0))
            assertTrue("device_id should stay NULL for an ambiguous shared driver_id", cursor.isNull(1))
        }
    }

    @Test
    fun `distinct recorded_at values are never merged`() {
        helper.createDatabase(TEST_DB, 25).apply {
            insertHrv(this, id = 1, recordedAt = 5000, driverId = "no_such_driver", deviceId = null, rmssdMs = 60.0)
            insertHrv(this, id = 2, recordedAt = 5001, driverId = "no_such_driver", deviceId = null, rmssdMs = 61.0)
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 26, true, AppDatabase.MIGRATION_25_26)

        migrated.query("SELECT COUNT(*) FROM hrv_readings").use { cursor ->
            cursor.moveToFirst()
            assertEquals(2, cursor.getInt(0))
        }
    }

    @Test
    fun `pattern generalizes to hr_readings`() {
        helper.createDatabase(TEST_DB, 25).apply {
            insertDevice(this, id = 40, address = "AA:BB:CC:DD:EE:05", driverId = "hr_driver")
            execSQL(
                "INSERT INTO hr_readings (id, recorded_at, created_at, source, driver_id, device_id, bpm) " +
                    "VALUES (1, 6000, 6000, 'DEVICE', 'hr_driver', NULL, 60)"
            )
            execSQL(
                "INSERT INTO hr_readings (id, recorded_at, created_at, source, driver_id, device_id, bpm) " +
                    "VALUES (2, 6000, 6000, 'DEVICE', 'hr_driver', NULL, 60)"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 26, true, AppDatabase.MIGRATION_25_26)

        migrated.query("SELECT id, device_id FROM hr_readings WHERE recorded_at = 6000").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals(2L, cursor.getLong(0))
            assertEquals(40L, cursor.getLong(1))
        }
    }

    private companion object {
        const val TEST_DB = "migration-25-26-test"
    }
}
