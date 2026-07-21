package com.athletedata.openAthleteMetrics.data.repository

import androidx.work.WorkManager
import com.athletedata.openAthleteMetrics.data.db.MetricReadingStagingDao
import com.athletedata.openAthleteMetrics.data.db.MetricReadingStagingEntity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.data.model.MetricReading
import com.athletedata.openAthleteMetrics.data.model.MetricType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import java.time.Instant

class RoomMetricReadingStagingRepositoryTest {

    private lateinit var dao: MetricReadingStagingDao
    private lateinit var workManager: WorkManager
    private lateinit var repository: RoomMetricReadingStagingRepository

    @Before
    fun setUp() {
        dao = mockk(relaxed = true)
        workManager = mockk(relaxed = true)
        repository = RoomMetricReadingStagingRepository(dao, workManager)
    }

    private fun manualReading(recordedAt: Instant = Instant.parse("2026-07-21T09:00:00Z")) = MetricReading(
        metricType = MetricType.GLUCOSE,
        value = 5.4,
        unit = "mmol",
        recordedAt = recordedAt,
        createdAt = Instant.now(),
        source = DataSource.MANUAL,
        driverId = null,
    )

    // ---------------------------------------------------------------------------
    // Reference: Bugs.md — manual entries have driver_id = null, which SQLite's
    // unique index on (driver_id, metric_type, recorded_at) can't dedupe (every
    // NULL is distinct). insertManual must guard explicitly.
    // ---------------------------------------------------------------------------

    @Test
    fun `insertManual inserts when no existing row for the key`() = runBlocking {
        coEvery { dao.getByDriverTypeAndDate(null, MetricType.GLUCOSE, any()) } returns null

        repository.insertManual(manualReading())

        coVerify(exactly = 1) { dao.insert(any()) }
    }

    @Test
    fun `insertManual skips insert when a row already exists for the key`() = runBlocking {
        val recordedAt = Instant.parse("2026-07-21T09:00:00Z")
        val existing = MetricReadingStagingEntity(
            id = 1,
            metricType = MetricType.GLUCOSE,
            value = 5.4,
            unit = "mmol",
            recordedAt = recordedAt,
            createdAt = Instant.now(),
            source = DataSource.MANUAL,
            driverId = null,
        )
        coEvery { dao.getByDriverTypeAndDate(null, MetricType.GLUCOSE, recordedAt) } returns existing

        repository.insertManual(manualReading(recordedAt))

        coVerify(exactly = 0) { dao.insert(any()) }
    }
}
