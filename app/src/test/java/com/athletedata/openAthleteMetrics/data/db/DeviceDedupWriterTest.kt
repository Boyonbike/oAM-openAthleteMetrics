package com.athletedata.openAthleteMetrics.data.db

import android.app.Application
import androidx.room.Room
import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Exercises the real DAOs' @Insert(onConflict = REPLACE) methods against the current
 * @Entity unique-index declaration on hr_readings, sleep_sessions and activities.
 * Against the pre-flip (driver_id, X) index these "different device" tests collapse to
 * one row; against the post-flip (device_id, X) index they persist as two -- this is the
 * live proof that the dedup semantics actually changed, not just the migration SQL.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DeviceDedupWriterTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── hr_readings ──────────────────────────────────────────────────────────────

    @Test
    fun `hrReadingDao insert with same driver_id and recorded_at but different device_id keeps both rows`() = runBlocking {
        val recordedAt = Instant.parse("2026-02-10T08:00:00Z")
        db.hrReadingDao().insert(
            HrReadingEntity(recordedAt = recordedAt, createdAt = recordedAt, source = DataSource.DEVICE, driverId = "drv", deviceId = 1L, bpm = 60)
        )
        db.hrReadingDao().insert(
            HrReadingEntity(recordedAt = recordedAt, createdAt = recordedAt, source = DataSource.DEVICE, driverId = "drv", deviceId = 2L, bpm = 65)
        )

        val rows = db.hrReadingDao().getReadingsInRangeOnce(
            recordedAt.minusSeconds(1).toEpochMilli(),
            recordedAt.plusSeconds(1).toEpochMilli(),
        )
        assertEquals(2, rows.size)
        assertEquals(setOf(1L, 2L), rows.map { it.deviceId }.toSet())
    }

    @Test
    fun `hrReadingDao insert with same device_id and recorded_at replaces the row`() = runBlocking {
        val recordedAt = Instant.parse("2026-02-10T08:00:00Z")
        db.hrReadingDao().insert(
            HrReadingEntity(recordedAt = recordedAt, createdAt = recordedAt, source = DataSource.DEVICE, driverId = "drv", deviceId = 1L, bpm = 60)
        )
        db.hrReadingDao().insert(
            HrReadingEntity(recordedAt = recordedAt, createdAt = recordedAt, source = DataSource.DEVICE, driverId = "drv", deviceId = 1L, bpm = 65)
        )

        val rows = db.hrReadingDao().getReadingsInRangeOnce(
            recordedAt.minusSeconds(1).toEpochMilli(),
            recordedAt.plusSeconds(1).toEpochMilli(),
        )
        assertEquals(1, rows.size)
        assertEquals(65, rows.single().bpm)
    }

    @Test
    fun `hrReadingDao insertAll REPLACE reprocessing of already-backfilled rows does not duplicate`() = runBlocking {
        val base = Instant.parse("2026-02-10T08:00:00Z")
        val entities = (0 until 3).map { i ->
            HrReadingEntity(
                recordedAt = base.plusSeconds(i.toLong()), createdAt = base,
                source = DataSource.DEVICE, driverId = "drv", deviceId = 7L, bpm = 60 + i,
            )
        }
        db.hrReadingDao().insertAll(entities)
        db.hrReadingDao().insertAll(entities)

        val rows = db.hrReadingDao().getReadingsInRangeOnce(
            base.minusSeconds(1).toEpochMilli(),
            base.plusSeconds(10).toEpochMilli(),
        )
        assertEquals(3, rows.size)
    }

    // ── sleep_sessions ───────────────────────────────────────────────────────────

    @Test
    fun `sleepSessionDao insert with same driver_id and date but different device_id keeps both rows`() = runBlocking {
        val date = LocalDate.of(2026, 2, 10)
        val start = Instant.parse("2026-02-10T00:00:00Z")
        val end = start.plus(8, ChronoUnit.HOURS)
        db.sleepSessionDao().insert(
            SleepSessionEntity(date = date, sleepStartMs = start, sleepEndMs = end, durationMinutes = 480, source = DataSource.DEVICE, driverId = "drv", deviceId = 1L)
        )
        db.sleepSessionDao().insert(
            SleepSessionEntity(date = date, sleepStartMs = start, sleepEndMs = end, durationMinutes = 480, source = DataSource.DEVICE, driverId = "drv", deviceId = 2L)
        )

        val rows = db.sleepSessionDao().getSessionsForRange(date, date).first()
        assertEquals(2, rows.size)
        assertEquals(setOf(1L, 2L), rows.map { it.deviceId }.toSet())
    }

    @Test
    fun `sleepSessionDao insert with same device_id and date replaces the row`() = runBlocking {
        val date = LocalDate.of(2026, 2, 10)
        val start = Instant.parse("2026-02-10T00:00:00Z")
        val end = start.plus(8, ChronoUnit.HOURS)
        db.sleepSessionDao().insert(
            SleepSessionEntity(date = date, sleepStartMs = start, sleepEndMs = end, durationMinutes = 480, source = DataSource.DEVICE, driverId = "drv", deviceId = 1L)
        )
        db.sleepSessionDao().insert(
            SleepSessionEntity(date = date, sleepStartMs = start, sleepEndMs = end, durationMinutes = 420, source = DataSource.DEVICE, driverId = "drv", deviceId = 1L)
        )

        val rows = db.sleepSessionDao().getSessionsForRange(date, date).first()
        assertEquals(1, rows.size)
        assertEquals(420, rows.single().durationMinutes)
    }

    // ── activities ───────────────────────────────────────────────────────────────

    @Test
    fun `activityDao insert with same driver_id and start_time but different device_id keeps both rows`() = runBlocking {
        val startTime = Instant.parse("2026-02-10T08:00:00Z")
        val endTime = startTime.plus(30, ChronoUnit.MINUTES)
        db.activityDao().insert(
            ActivityEntity(startTime = startTime, endTime = endTime, durationMinutes = 30, deviceName = "Band A", source = DataSource.DEVICE, driverId = "drv", deviceId = 1L)
        )
        db.activityDao().insert(
            ActivityEntity(startTime = startTime, endTime = endTime, durationMinutes = 30, deviceName = "Band B", source = DataSource.DEVICE, driverId = "drv", deviceId = 2L)
        )

        val rows = db.activityDao().getActivitiesInRangeOnce(
            startTime.minusSeconds(1).toEpochMilli(),
            startTime.plusSeconds(1).toEpochMilli(),
        )
        assertEquals(2, rows.size)
        assertEquals(setOf(1L, 2L), rows.map { it.deviceId }.toSet())
    }

    @Test
    fun `activityDao insert with same device_id and start_time replaces the row`() = runBlocking {
        val startTime = Instant.parse("2026-02-10T08:00:00Z")
        val endTime = startTime.plus(30, ChronoUnit.MINUTES)
        db.activityDao().insert(
            ActivityEntity(startTime = startTime, endTime = endTime, durationMinutes = 30, deviceName = "Band A", source = DataSource.DEVICE, driverId = "drv", deviceId = 1L)
        )
        db.activityDao().insert(
            ActivityEntity(startTime = startTime, endTime = endTime, durationMinutes = 45, deviceName = "Band A", source = DataSource.DEVICE, driverId = "drv", deviceId = 1L)
        )

        val rows = db.activityDao().getActivitiesInRangeOnce(
            startTime.minusSeconds(1).toEpochMilli(),
            startTime.plusSeconds(1).toEpochMilli(),
        )
        assertEquals(1, rows.size)
        assertEquals(45, rows.single().durationMinutes)
    }
}
