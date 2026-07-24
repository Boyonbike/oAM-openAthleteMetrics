package com.athletedata.openAthleteMetrics.data.repository

import android.app.Application
import androidx.room.Room
import com.athletedata.openAthleteMetrics.data.db.AppDatabase
import com.athletedata.openAthleteMetrics.data.db.MetricDailyStatsEntity
import com.athletedata.openAthleteMetrics.data.model.BaselineMetric
import com.athletedata.openAthleteMetrics.data.model.DailySummary
import com.athletedata.openAthleteMetrics.data.model.DataSource
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

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MetricDailyStatsWriterTest {

    private lateinit var db: AppDatabase
    private lateinit var summaryRepo: DailySummaryRepository
    private lateinit var windowConfigRepo: BaselineWindowConfigRepository
    private lateinit var writer: MetricDailyStatsWriter
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
        writer = MetricDailyStatsWriter(db.metricDailyStatsDao(), calculators)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun insertHr(date: LocalDate, avgHrBpm: Double) = runBlocking {
        summaryRepo.upsert(DailySummary(date = date, avgHrBpm = avgHrBpm, source = DataSource.DEVICE, computedAt = Instant.now()))
    }

    private suspend fun rowFor(metric: BaselineMetric, date: LocalDate): MetricDailyStatsEntity =
        db.metricDailyStatsDao().getRange(metric, date, date).first().single()

    @Test
    fun `write produces one row per requested metric anchored to the given date`() = runBlocking {
        windowConfigRepo.setOverride(BaselineMetric.HR, windowDays = 5, minimumDays = 1)
        windowConfigRepo.setOverride(BaselineMetric.RHR, windowDays = 5, minimumDays = 1)
        val anchor = LocalDate.of(2026, 2, 10)
        insertHr(anchor, 60.0)

        writer.write(anchor, zone, setOf(BaselineMetric.HR, BaselineMetric.RHR))

        val hrRow = rowFor(BaselineMetric.HR, anchor)
        assertEquals(anchor, hrRow.summaryDate)
        assertEquals(BaselineMetric.HR, hrRow.metricType)
        val rhrRow = rowFor(BaselineMetric.RHR, anchor)
        assertEquals(anchor, rhrRow.summaryDate)
        assertEquals(BaselineMetric.RHR, rhrRow.metricType)
    }

    @Test
    fun `writing a later date does not touch an earlier date's own row, which correctly reflects only its own backward window`() = runBlocking {
        windowConfigRepo.setOverride(BaselineMetric.HR, windowDays = 2, minimumDays = 1)
        val dMinus2 = LocalDate.of(2026, 2, 8)
        val dMinus1 = LocalDate.of(2026, 2, 9)
        val d = LocalDate.of(2026, 2, 10)
        insertHr(dMinus2, 50.0)
        insertHr(dMinus1, 60.0)
        insertHr(d, 70.0)

        // Process in chronological order, as DailySummaryWorker would.
        writer.write(dMinus1, zone, setOf(BaselineMetric.HR))
        val dMinus1RowBefore = rowFor(BaselineMetric.HR, dMinus1)
        assertEquals(2, dMinus1RowBefore.sampleCount) // window=2: [D-2, D-1]

        writer.write(d, zone, setOf(BaselineMetric.HR))
        val dRow = rowFor(BaselineMetric.HR, d)
        // window=2 anchored at D: [D-1, D] -- includes D-1, excludes D-2.
        assertEquals(2, dRow.sampleCount)
        assertEquals(65.0, dRow.mean!!, 1e-9) // (60 + 70) / 2

        // D-1's own row, computed before D existed, is byte-for-byte untouched by writing D --
        // write() never touches a date's row other than the one it was called for.
        val dMinus1RowAfter = rowFor(BaselineMetric.HR, dMinus1)
        assertEquals(dMinus1RowBefore, dMinus1RowAfter)
    }

    @Test
    fun `one metric's calculator throwing does not block the others in the same write`() = runBlocking {
        windowConfigRepo.setOverride(BaselineMetric.HR, windowDays = 5, minimumDays = 1)
        val anchor = LocalDate.of(2026, 2, 10)
        insertHr(anchor, 60.0)

        val throwingCalculator = object : MetricStatsCalculator {
            override suspend fun compute(date: LocalDate, zone: ZoneId): MetricDailyStatsEntity? =
                throw IllegalStateException("boom")
        }
        val mixedWriter = MetricDailyStatsWriter(
            db.metricDailyStatsDao(),
            mapOf(
                BaselineMetric.HR to GenericTrailingStatsCalculator(BaselineMetric.HR, summaryRepo, windowConfigRepo) { it.avgHrBpm },
                BaselineMetric.RHR to throwingCalculator,
            ),
        )

        // Must not throw, and must still write HR's row despite RHR's calculator failing.
        mixedWriter.write(anchor, zone, setOf(BaselineMetric.HR, BaselineMetric.RHR))

        val hrRow = rowFor(BaselineMetric.HR, anchor)
        assertNotEquals(0, hrRow.sampleCount)
        assertEquals(0, db.metricDailyStatsDao().getAllForMetricOnce(BaselineMetric.RHR).size)
    }
}
