package com.athletedata.openAthleteMetrics.data.db

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Real in-memory Room tests for [DeviceDao]'s primary/auto-sync invariant methods.
 * setPrimary is the only write path to is_primary, so its single-primary + auto-sync
 * transition rule is exercised directly against the DAO (not mocked) per this codebase's
 * convention -- SQL/transaction bugs here are invisible to mocked-DAO tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DeviceDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: DeviceDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.deviceDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun insertDevice(bleAddress: String, driverId: String, displayName: String): Long = runBlocking {
        dao.upsert(DeviceEntity(bleAddress = bleAddress, driverId = driverId, displayName = displayName))
        dao.getDeviceByAddress(bleAddress)!!.id
    }

    @Test
    fun `setPrimary makes exactly one device primary`() = runBlocking {
        val a = insertDevice("AA:01", "driver_a", "Dev A")
        val b = insertDevice("AA:02", "driver_b", "Dev B")

        dao.setPrimary(a)

        assertTrue(dao.getById(a)!!.isPrimary)
        assertTrue(!dao.getById(b)!!.isPrimary)
    }

    @Test
    fun `setPrimary sets new primary auto_sync ON`() = runBlocking {
        val a = insertDevice("AA:03", "driver_a", "Dev A")
        dao.setAutoSync(a, false)

        dao.setPrimary(a)

        assertTrue(dao.getById(a)!!.autoSyncEnabled)
    }

    @Test
    fun `setPrimary flips old primary auto_sync OFF`() = runBlocking {
        val a = insertDevice("AA:04", "driver_a", "Dev A")
        val b = insertDevice("AA:05", "driver_b", "Dev B")
        dao.setPrimary(a)
        assertTrue(dao.getById(a)!!.autoSyncEnabled)

        dao.setPrimary(b)

        assertTrue(!dao.getById(a)!!.autoSyncEnabled)
        assertTrue(dao.getById(b)!!.autoSyncEnabled)
        assertTrue(dao.getById(b)!!.isPrimary)
        assertTrue(!dao.getById(a)!!.isPrimary)
    }

    @Test
    fun `setPrimary is idempotent for the already-primary device`() = runBlocking {
        val a = insertDevice("AA:06", "driver_a", "Dev A")
        dao.setPrimary(a)

        dao.setPrimary(a)

        assertTrue(dao.getById(a)!!.isPrimary)
        assertTrue(dao.getById(a)!!.autoSyncEnabled)
    }

    @Test
    fun `star A then star B then star A again stays consistent`() = runBlocking {
        val a = insertDevice("AA:07", "driver_a", "Dev A")
        val b = insertDevice("AA:08", "driver_b", "Dev B")

        dao.setPrimary(a)
        assertTrue(dao.getById(a)!!.isPrimary)
        assertTrue(dao.getById(a)!!.autoSyncEnabled)
        assertTrue(!dao.getById(b)!!.isPrimary)

        dao.setPrimary(b)
        assertTrue(!dao.getById(a)!!.isPrimary)
        assertTrue(!dao.getById(a)!!.autoSyncEnabled)
        assertTrue(dao.getById(b)!!.isPrimary)
        assertTrue(dao.getById(b)!!.autoSyncEnabled)

        dao.setPrimary(a)
        assertTrue(dao.getById(a)!!.isPrimary)
        assertTrue(dao.getById(a)!!.autoSyncEnabled)
        assertTrue(!dao.getById(b)!!.isPrimary)
        assertTrue(!dao.getById(b)!!.autoSyncEnabled)
    }

    @Test
    fun `setAutoSync toggles without changing is_primary on the primary device`() = runBlocking {
        val a = insertDevice("AA:09", "driver_a", "Dev A")
        dao.setPrimary(a)

        dao.setAutoSync(a, false)
        assertTrue(dao.getById(a)!!.isPrimary)
        assertTrue(!dao.getById(a)!!.autoSyncEnabled)

        dao.setAutoSync(a, true)
        assertTrue(dao.getById(a)!!.isPrimary)
        assertTrue(dao.getById(a)!!.autoSyncEnabled)
    }

    @Test
    fun `setAutoSync on a non-primary device works independently`() = runBlocking {
        val a = insertDevice("AA:10", "driver_a", "Dev A")
        val b = insertDevice("AA:11", "driver_b", "Dev B")
        dao.setPrimary(a)

        dao.setAutoSync(b, false)

        assertTrue(!dao.getById(b)!!.isPrimary)
        assertTrue(!dao.getById(b)!!.autoSyncEnabled)
        assertTrue(dao.getById(a)!!.isPrimary)
        assertTrue(dao.getById(a)!!.autoSyncEnabled)
    }

    @Test
    fun `observePrimary emits the current primary and updates when setPrimary changes it`() = runBlocking {
        val a = insertDevice("AA:12", "driver_a", "Dev A")
        val b = insertDevice("AA:13", "driver_b", "Dev B")

        dao.setPrimary(a)
        assertEquals(a, dao.observePrimary().first()!!.id)

        dao.setPrimary(b)
        assertEquals(b, dao.observePrimary().first()!!.id)
    }

    @Test
    fun `getPrimary returns null when no device is primary`() = runBlocking {
        insertDevice("AA:14", "driver_a", "Dev A")

        assertNull(dao.getPrimary())
    }
}
