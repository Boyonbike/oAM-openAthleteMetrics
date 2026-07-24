package com.athletedata.openAthleteMetrics.data.repository

import android.app.Application
import androidx.room.Room
import com.athletedata.openAthleteMetrics.data.db.AppDatabase
import com.athletedata.openAthleteMetrics.data.model.BaselineMetric
import com.athletedata.openAthleteMetrics.data.model.DailySummary
import com.athletedata.openAthleteMetrics.data.model.DataSource
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.sqrt

/**
 * Real in-memory Room database (per SleepStagePromoterTest's pattern) so the trailing-window
 * `getSummariesForRange` SQL predicate is exercised end-to-end, not just mocked away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class GenericTrailingStatsCalculatorTest {

    private lateinit var db: AppDatabase
    private lateinit var summaryRepo: DailySummaryRepository
    private lateinit var windowConfigRepo: BaselineWindowConfigRepository
    private val zone: ZoneId = ZoneId.systemDefault()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        summaryRepo = FakeDailySummaryRepository(db.dailySummaryDao())
        val settingsRepo = mockk<SettingsRepository>(relaxed = true) {
            every { getBaselineWindowDays() } returns flowOf(30)
        }
        windowConfigRepo = RoomBaselineWindowConfigRepository(db.baselineWindowConfigDao(), settingsRepo)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun insertHr(date: LocalDate, avgHrBpm: Double) = runBlocking {
        summaryRepo.upsert(
            DailySummary(date = date, avgHrBpm = avgHrBpm, source = DataSource.DEVICE, computedAt = Instant.now())
        )
    }

    @Test
    fun `days outside the trailing window are excluded`() = runBlocking {
        windowConfigRepo.setOverride(BaselineMetric.HR, windowDays = 5, minimumDays = 3)
        val anchor = LocalDate.of(2026, 1, 10)

        // In-window: Jan 6-10 inclusive (anchor - 4 .. anchor).
        insertHr(LocalDate.of(2026, 1, 6), 60.0)
        insertHr(LocalDate.of(2026, 1, 7), 61.0)
        insertHr(LocalDate.of(2026, 1, 8), 62.0)
        insertHr(LocalDate.of(2026, 1, 9), 63.0)
        insertHr(anchor, 64.0)
        // Out-of-window on both sides.
        insertHr(LocalDate.of(2026, 1, 5), 999.0)
        insertHr(LocalDate.of(2026, 1, 11), 888.0)

        val calculator = GenericTrailingStatsCalculator(
            BaselineMetric.HR, summaryRepo, windowConfigRepo,
        ) { it.avgHrBpm }
        val row = calculator.compute(anchor, zone)

        assertEquals(5, row.sampleCount)
        assertEquals(62.0, row.mean!!, 1e-9)
    }

    @Test
    fun `below minimum-days gate yields null mean and std_dev`() = runBlocking {
        val metric = BaselineMetric.HR
        windowConfigRepo.setOverride(metric, windowDays = 5, minimumDays = 3)
        val anchor = LocalDate.of(2026, 1, 10)

        insertHr(anchor, 60.0)
        insertHr(LocalDate.of(2026, 1, 9), 61.0)
        // Only 2 in-window days -- below the minimum of 3.

        val calculator = GenericTrailingStatsCalculator(metric, summaryRepo, windowConfigRepo) { it.avgHrBpm }
        val row = calculator.compute(anchor, zone)

        assertEquals(2, row.sampleCount)
        assertNull(row.mean)
        assertNull(row.stdDev)
    }

    @Test
    fun `exactly at minimum-days threshold is populated`() = runBlocking {
        val metric = BaselineMetric.HR
        windowConfigRepo.setOverride(metric, windowDays = 5, minimumDays = 3)
        val anchor = LocalDate.of(2026, 1, 10)

        insertHr(anchor, 60.0)
        insertHr(LocalDate.of(2026, 1, 9), 61.0)
        insertHr(LocalDate.of(2026, 1, 8), 62.0)

        val calculator = GenericTrailingStatsCalculator(metric, summaryRepo, windowConfigRepo) { it.avgHrBpm }
        val row = calculator.compute(anchor, zone)

        assertEquals(3, row.sampleCount)
        assertNotEquals(null, row.mean)
        assertNotEquals(null, row.stdDev)
    }

    @Test
    fun `std_dev divides by population size n, not sample n-1`() = runBlocking {
        val metric = BaselineMetric.HR
        windowConfigRepo.setOverride(metric, windowDays = 3, minimumDays = 3)
        val anchor = LocalDate.of(2026, 1, 10)

        // Values 10, 20, 30 -> mean 20.
        // Population variance = ((10*100)+(0)+(100))/3 = 200/3 -> stdDev = sqrt(200/3).
        // Sample (n-1) variance = 200/2 = 100 -> stdDev = 10.0 (must NOT match this).
        insertHr(LocalDate.of(2026, 1, 8), 10.0)
        insertHr(LocalDate.of(2026, 1, 9), 20.0)
        insertHr(anchor, 30.0)

        val calculator = GenericTrailingStatsCalculator(metric, summaryRepo, windowConfigRepo) { it.avgHrBpm }
        val row = calculator.compute(anchor, zone)

        val expectedPopulationStdDev = sqrt(200.0 / 3.0)
        val sampleStdDev = 10.0
        assertEquals(expectedPopulationStdDev, row.stdDev!!, 1e-9)
        assertNotEquals(sampleStdDev, row.stdDev!!, 1e-9)
    }
}
