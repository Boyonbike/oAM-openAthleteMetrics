package com.athletedata.openAthleteMetrics.worker

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.athletedata.openAthleteMetrics.data.db.AppDatabase
import com.athletedata.openAthleteMetrics.data.db.HrReadingEntity
import com.athletedata.openAthleteMetrics.data.model.BaselineMetric
import com.athletedata.openAthleteMetrics.data.model.DailySummary
import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.data.repository.FakeDailySummaryRepository
import com.athletedata.openAthleteMetrics.data.repository.GenericTrailingStatsCalculator
import com.athletedata.openAthleteMetrics.data.repository.MetricDailyStatsWriter
import com.athletedata.openAthleteMetrics.data.repository.MetricStatsCalculator
import com.athletedata.openAthleteMetrics.data.repository.RoomBaselineWindowConfigRepository
import com.athletedata.openAthleteMetrics.data.repository.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Regression guard for the point-in-time bug this session fixes: the write path anchoring a
 * trailing-window computation to [LocalDate.now] instead of the date actually being processed.
 * Uses a date far from wall-clock "today" so a now()-anchored regression would produce an
 * obviously wrong (or empty) result rather than an accidental pass.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DailySummaryWorkerMetricStatsTest {

    private lateinit var db: AppDatabase
    private val zone: ZoneId = ZoneId.systemDefault()

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

    private fun insertHr(date: LocalDate, hour: Int, bpm: Int) = runBlocking {
        db.hrReadingDao().insert(
            HrReadingEntity(
                recordedAt = date.atTime(hour, 0).atZone(zone).toInstant(),
                createdAt = Instant.now(),
                source = DataSource.SEEDER,
                bpm = bpm,
            )
        )
    }

    @Test
    fun `processing a backfilled past date writes metric_daily_stats for that date's own window, not today's`() = runBlocking {
        val settingsRepo = mockk<SettingsRepository>(relaxed = true) {
            every { getBaselineWindowDays() } returns flowOf(30)
        }
        val windowConfigRepo = RoomBaselineWindowConfigRepository(db.baselineWindowConfigDao(), settingsRepo)
        windowConfigRepo.setOverride(BaselineMetric.HR, windowDays = 3, minimumDays = 1)

        // 2020 -- deliberately unrelated to the environment's actual current date, so a
        // LocalDate.now()-anchored regression would read an empty window, not this data.
        val day1 = LocalDate.of(2020, 1, 1)
        val day2 = LocalDate.of(2020, 1, 2)
        val day3 = LocalDate.of(2020, 1, 3)

        val summaryRepo = FakeDailySummaryRepository(db.dailySummaryDao())
        // day1/day2's own daily_summary rows already exist (as if their own worker runs already
        // completed, matching real in-order ingestion) -- only day3 is processed by this worker
        // run, exercising the raw-reading-aggregation path for the date actually under test.
        summaryRepo.upsert(DailySummary(date = day1, avgHrBpm = 50.0, source = DataSource.SEEDER, computedAt = Instant.now()))
        summaryRepo.upsert(DailySummary(date = day2, avgHrBpm = 60.0, source = DataSource.SEEDER, computedAt = Instant.now()))
        insertHr(day3, hour = 8, bpm = 70)

        val calculators: Map<BaselineMetric, MetricStatsCalculator> = mapOf(
            BaselineMetric.HR to GenericTrailingStatsCalculator(BaselineMetric.HR, summaryRepo, windowConfigRepo) { it.avgHrBpm },
        )
        val statsWriter = MetricDailyStatsWriter(db.metricDailyStatsDao(), calculators)

        val context = RuntimeEnvironment.getApplication()
        val inputData = workDataOf(DailySummaryWorker.KEY_DATE to day3.toString())
        val worker = TestListenableWorkerBuilder<DailySummaryWorker>(context)
            .setInputData(inputData)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = DailySummaryWorker(
                    appContext, workerParameters,
                    db.hrReadingDao(), db.hrvReadingDao(), db.spO2ReadingDao(), db.skinTempReadingDao(),
                    db.respirationReadingDao(), db.stepsReadingDao(), db.activeCalorieReadingDao(),
                    db.totalCalorieReadingDao(), db.bloodPressureReadingDao(), db.glucoseReadingDao(),
                    db.sleepSessionDao(), db.sleepStageDao(),
                    summaryRepo,
                    OvernightHrvCalculator(db.hrvReadingDao(), db.sleepSessionDao()),
                    statsWriter,
                )
            })
            .build()

        val result = worker.startWork().get()
        assertTrue("worker should succeed", result is ListenableWorker.Result.Success)

        val row = db.metricDailyStatsDao().getRange(BaselineMetric.HR, day3, day3).first().single()
        assertEquals(3, row.sampleCount)
        assertEquals(60.0, row.mean!!, 1e-9) // average(50, 60, 70) -- day3's own backward window
    }
}
