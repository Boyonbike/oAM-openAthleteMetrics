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

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SleepStageStatsCalculatorTest {

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

    private fun insertNight(date: LocalDate, deep: Int, light: Int, rem: Int, awake: Int) = runBlocking {
        summaryRepo.upsert(
            DailySummary(
                date = date,
                sleepDeepMinutes = deep,
                sleepLightMinutes = light,
                sleepRemMinutes = rem,
                sleepAwakeMinutes = awake,
                source = DataSource.DEVICE,
                computedAt = Instant.now(),
            )
        )
    }

    @Test
    fun `mean minutes and mean_pct are both populated, using average-of-ratios not ratio-of-averages`() = runBlocking {
        val metric = BaselineMetric.SLEEP_DEEP
        windowConfigRepo.setOverride(metric, windowDays = 2, minimumDays = 2)
        val anchor = LocalDate.of(2026, 1, 10)

        // Night 1 (D-1): deep=100 of a 200-minute night -> ratio 50%.
        insertNight(anchor.minusDays(1), deep = 100, light = 50, rem = 30, awake = 20)
        // Night 2 (D): deep=10 of a 100-minute night -> ratio 10%.
        insertNight(anchor, deep = 10, light = 50, rem = 30, awake = 10)

        val calculator = SleepStageStatsCalculator(metric, summaryRepo, windowConfigRepo) { it.sleepDeepMinutes }
        val row = calculator.compute(anchor, zone)

        assertEquals(2, row.sampleCount)
        // Mean minutes: average of the raw per-night stage minutes.
        assertEquals(55.0, row.mean!!, 1e-9)
        // Average-of-ratios: (50% + 10%) / 2 = 30%.
        val averageOfRatios = 30.0
        assertEquals(averageOfRatios, row.meanPct!!, 1e-9)
        // Ratio-of-averages would instead be sum(deep)/sum(total) = 110/300 = 36.67% -- must differ.
        val ratioOfAverages = 110.0 / 300.0 * 100.0
        assertNotEquals(ratioOfAverages, row.meanPct!!, 1e-9)
    }

    @Test
    fun `night with a non-positive stage total is excluded from both averages`() = runBlocking {
        val metric = BaselineMetric.SLEEP_DEEP
        windowConfigRepo.setOverride(metric, windowDays = 2, minimumDays = 1)
        val anchor = LocalDate.of(2026, 1, 10)

        insertNight(anchor.minusDays(1), deep = 0, light = 0, rem = 0, awake = 0) // total <= 0, excluded
        insertNight(anchor, deep = 40, light = 40, rem = 10, awake = 10) // total 100, ratio 40%

        val calculator = SleepStageStatsCalculator(metric, summaryRepo, windowConfigRepo) { it.sleepDeepMinutes }
        val row = calculator.compute(anchor, zone)

        assertEquals(1, row.sampleCount)
        assertEquals(40.0, row.mean!!, 1e-9)
        assertEquals(40.0, row.meanPct!!, 1e-9)
    }
}
