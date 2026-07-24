package com.athletedata.openAthleteMetrics.data.repository

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.athletedata.openAthleteMetrics.data.db.AppDatabase
import com.athletedata.openAthleteMetrics.data.db.MetricDailyStatsEntity
import com.athletedata.openAthleteMetrics.data.model.BaselineMetric
import com.athletedata.openAthleteMetrics.data.model.DailySummary
import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.worker.MetricStatsRecomputeWorker
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
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

/**
 * Exercises [MetricStatsBackfillCoordinator] end-to-end through a real, synchronously-executing
 * [WorkManager] test instance (per androidx.work:work-testing), so enqueuing a recompute actually
 * runs [MetricStatsRecomputeWorker] and lands rows in the database -- not just a mocked
 * "enqueue was called" assertion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MetricStatsBackfillCoordinatorTest {

    private lateinit var db: AppDatabase
    private lateinit var summaryRepo: DailySummaryRepository
    private lateinit var windowConfigRepo: BaselineWindowConfigRepository
    private lateinit var workManager: WorkManager
    private lateinit var coordinator: MetricStatsBackfillCoordinator
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
        val calculators: Map<BaselineMetric, MetricStatsCalculator> = mapOf(
            BaselineMetric.HR to GenericTrailingStatsCalculator(BaselineMetric.HR, summaryRepo, windowConfigRepo) { it.avgHrBpm },
            BaselineMetric.RHR to GenericTrailingStatsCalculator(BaselineMetric.RHR, summaryRepo, windowConfigRepo) { it.restingHrBpm },
        )
        val writer = MetricDailyStatsWriter(db.metricDailyStatsDao(), calculators)

        val context = RuntimeEnvironment.getApplication()
        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker? = when (workerClassName) {
                    MetricStatsRecomputeWorker::class.java.name ->
                        MetricStatsRecomputeWorker(appContext, workerParameters, writer)
                    else -> null
                }
            })
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)

        coordinator = MetricStatsBackfillCoordinator(summaryRepo, windowConfigRepo, db.metricDailyStatsDao(), calculators, workManager)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun insert(date: LocalDate, avgHrBpm: Double, restingHrBpm: Double) = runBlocking {
        summaryRepo.upsert(
            DailySummary(date = date, avgHrBpm = avgHrBpm, restingHrBpm = restingHrBpm, source = DataSource.DEVICE, computedAt = Instant.now())
        )
    }

    private suspend fun rowFor(metric: BaselineMetric, date: LocalDate): MetricDailyStatsEntity =
        db.metricDailyStatsDao().getRange(metric, date, date).first().single()

    /**
     * CoroutineWorker.doWork() runs on its own dispatcher even under a SynchronousExecutor
     * Configuration (that executor only governs WorkManager's own scheduling, not the worker's
     * coroutine body) -- so enqueueUniqueWork() can return before the recompute actually lands.
     * Poll until every tagged work item has finished before asserting on its effects.
     */
    private fun awaitAllRecomputeWork() {
        var attempts = 0
        while (attempts < 500) {
            val infos = workManager.getWorkInfosByTag(MetricStatsRecomputeWorker.WORK_TAG).get()
            if (infos.all { it.state.isFinished }) return
            Thread.sleep(10)
            attempts++
        }
        error("Timed out waiting for metric_stats_recompute work to finish: ${workManager.getWorkInfosByTag(MetricStatsRecomputeWorker.WORK_TAG).get()}")
    }

    private suspend fun writeAll(dates: List<LocalDate>, metrics: Collection<BaselineMetric>) {
        val calculators: Map<BaselineMetric, MetricStatsCalculator> = mapOf(
            BaselineMetric.HR to GenericTrailingStatsCalculator(BaselineMetric.HR, summaryRepo, windowConfigRepo) { it.avgHrBpm },
            BaselineMetric.RHR to GenericTrailingStatsCalculator(BaselineMetric.RHR, summaryRepo, windowConfigRepo) { it.restingHrBpm },
        )
        val writer = MetricDailyStatsWriter(db.metricDailyStatsDao(), calculators)
        for (date in dates) writer.write(date, zone, metrics)
    }

    @Test
    fun `backfill span recompute is bounded per metric's own window and leaves rows outside it untouched`() = runBlocking {
        windowConfigRepo.setOverride(BaselineMetric.HR, windowDays = 2, minimumDays = 1)
        windowConfigRepo.setOverride(BaselineMetric.RHR, windowDays = 4, minimumDays = 1)

        val days = (1..8).map { LocalDate.of(2026, 3, it) }
        days.forEachIndexed { idx, date -> insert(date, avgHrBpm = (idx + 1) * 10.0, restingHrBpm = (idx + 1) * 5.0) }

        // Simulate full history already computed in order, as DailySummaryWorker's per-day
        // hook would have done for each date as it arrived.
        writeAll(days, setOf(BaselineMetric.HR, BaselineMetric.RHR))
        val hrBeforeByDate = days.associateWith { rowFor(BaselineMetric.HR, it) }
        val rhrBeforeByDate = days.associateWith { rowFor(BaselineMetric.RHR, it) }

        // Correct day4's underlying summary (a late/corrected sync) -- both fields change.
        val day4 = days[3]
        insert(day4, avgHrBpm = 999.0, restingHrBpm = 999.0)

        coordinator.enqueueSpanRecompute(setOf(day4))
        awaitAllRecomputeWork()

        // HR window=2: span [day4, day5]. RHR window=4: span [day4, day7].
        val hrRecomputed = setOf(days[3], days[4])
        val rhrRecomputed = setOf(days[3], days[4], days[5], days[6])

        for (date in days) {
            val hrBefore = hrBeforeByDate.getValue(date)
            val hrAfter = rowFor(BaselineMetric.HR, date)
            if (date in hrRecomputed) {
                assertNotEquals("HR row for $date should have been recomputed", hrBefore, hrAfter)
            } else {
                assertEquals("HR row for $date should be untouched", hrBefore, hrAfter)
            }

            val rhrBefore = rhrBeforeByDate.getValue(date)
            val rhrAfter = rowFor(BaselineMetric.RHR, date)
            if (date in rhrRecomputed) {
                assertNotEquals("RHR row for $date should have been recomputed", rhrBefore, rhrAfter)
            } else {
                assertEquals("RHR row for $date should be untouched", rhrBefore, rhrAfter)
            }
        }

        // Each recomputed row is anchored to its own day: day5's HR row (window=2, [day4,day5])
        // must reflect day4's NEW value (999) averaged with day5's original value (50).
        val day5HrRow = rowFor(BaselineMetric.HR, days[4])
        assertEquals((999.0 + 50.0) / 2.0, day5HrRow.mean!!, 1e-9)
    }

    @Test
    fun `a representative multi-source date union (as a reprocess would produce) actually triggers recompute, not just a count`() = runBlocking {
        windowConfigRepo.setOverride(BaselineMetric.HR, windowDays = 3, minimumDays = 1)
        val days = (1..6).map { LocalDate.of(2026, 4, it) }
        days.forEachIndexed { idx, date -> insert(date, avgHrBpm = (idx + 1) * 10.0, restingHrBpm = (idx + 1) * 5.0) }
        writeAll(days, setOf(BaselineMetric.HR))
        val day2BeforeRow = rowFor(BaselineMetric.HR, days[1])

        // Mirrors DeviceReprocessor's `allDates = metricDates + activityDates + sleepDates` --
        // a reprocess touching day2 (as if via a metric reading) should invalidate its forward span.
        insert(days[1], avgHrBpm = 12345.0, restingHrBpm = 12345.0)
        val reprocessDatesAffected = setOf(days[1])

        coordinator.enqueueSpanRecompute(reprocessDatesAffected)
        awaitAllRecomputeWork()

        val day3Row = rowFor(BaselineMetric.HR, days[2]) // window=3: [day1,day3] includes day2
        assertEquals((10.0 + 12345.0 + 30.0) / 3.0, day3Row.mean!!, 1e-9)
        val day2AfterRow = rowFor(BaselineMetric.HR, days[1])
        assertNotEquals(day2BeforeRow, day2AfterRow)
    }

    @Test
    fun `changing one metric's window via override recomputes that metric's stale rows and leaves other metrics untouched`() = runBlocking {
        windowConfigRepo.setOverride(BaselineMetric.HR, windowDays = 5, minimumDays = 1)
        windowConfigRepo.setOverride(BaselineMetric.RHR, windowDays = 5, minimumDays = 1)
        val anchor = LocalDate.of(2026, 5, 10)
        insert(anchor, avgHrBpm = 60.0, restingHrBpm = 55.0)
        writeAll(listOf(anchor), setOf(BaselineMetric.HR, BaselineMetric.RHR))

        val hrBefore = rowFor(BaselineMetric.HR, anchor)
        val rhrBefore = rowFor(BaselineMetric.RHR, anchor)

        coordinator.setOverrideAndInvalidate(BaselineMetric.HR, windowDays = 10, minimumDays = 1)
        awaitAllRecomputeWork()

        val hrAfter = rowFor(BaselineMetric.HR, anchor)
        val rhrAfter = rowFor(BaselineMetric.RHR, anchor)

        assertNotEquals("HR's config_hash must change after its own override changes", hrBefore.configHash, hrAfter.configHash)
        assertEquals("RHR must be completely untouched by HR's config change", rhrBefore, rhrAfter)
    }
}
