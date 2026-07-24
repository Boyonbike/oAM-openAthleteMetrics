package com.athletedata.openAthleteMetrics.data.repository

import android.app.Application
import androidx.room.Room
import com.athletedata.openAthleteMetrics.data.db.AppDatabase
import com.athletedata.openAthleteMetrics.data.model.BaselineMetric
import com.athletedata.openAthleteMetrics.data.model.DailySummary
import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.data.model.SleepSession
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * Guards the [SleepDetailProvider] cutover from live [SleepAverageCalculator] recomputation to
 * reading stored `metric_daily_stats` rows for SLEEP/SLEEP_DEEP/SLEEP_LIGHT/SLEEP_REM/SLEEP_AWAKE.
 *
 * Confirms, for the same underlying session/summary data: (1) the stored-row-derived value
 * matches the live calculator's value for each of the 5 migrated metrics, and (2) onset/wake
 * still come from the live calculator, untouched by the migration (they have no stored row —
 * see CalculatorModule).
 *
 * SLEEP parity relies on `DailySummary.sleepMinutes` being a verbatim copy of
 * `SleepSession.durationMinutes` at write time (confirmed in DailySummaryWorker.kt — not an
 * independent computation), so this test seeds them equal, mirroring the real write path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SleepDetailProviderParityTest {

    private lateinit var db: AppDatabase
    private lateinit var summaryRepo: DailySummaryRepository
    private lateinit var sleepRepo: SleepRepository
    private lateinit var windowConfigRepo: BaselineWindowConfigRepository
    private lateinit var sleepAverageCalculator: SleepAverageCalculator
    private lateinit var provider: SleepDetailProvider
    private val zone: ZoneId = ZoneId.systemDefault()

    private val stageMetrics = listOf(
        BaselineMetric.SLEEP_DEEP to DailySummary::sleepDeepMinutes,
        BaselineMetric.SLEEP_LIGHT to DailySummary::sleepLightMinutes,
        BaselineMetric.SLEEP_REM to DailySummary::sleepRemMinutes,
        BaselineMetric.SLEEP_AWAKE to DailySummary::sleepAwakeMinutes,
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        summaryRepo = FakeDailySummaryRepository(db.dailySummaryDao())
        sleepRepo = FakeSleepRepository(db.sleepSessionDao())
        val sleepStageRepo = RoomSleepStageRepository(db.sleepStageDao())
        val settingsRepo = mockk<SettingsRepository>(relaxed = true) {
            every { getBaselineWindowDays() } returns flowOf(30)
        }
        windowConfigRepo = RoomBaselineWindowConfigRepository(db.baselineWindowConfigDao(), settingsRepo)
        sleepAverageCalculator = SleepAverageCalculator(sleepRepo, summaryRepo, windowConfigRepo)
        val metricDailyStatsReader = MetricDailyStatsReader(db.metricDailyStatsDao(), windowConfigRepo)
        provider = SleepDetailProvider(sleepRepo, sleepStageRepo, sleepAverageCalculator, summaryRepo, metricDailyStatsReader)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private data class Night(val date: LocalDate, val duration: Int, val deep: Int, val light: Int, val rem: Int, val awake: Int)

    private suspend fun seedNight(night: Night) {
        val start = night.date.minusDays(1).atTime(23, 0).atZone(zone).toInstant()
        val end = start.plusSeconds(night.duration * 60L)
        sleepRepo.insert(
            SleepSession(date = night.date, sleepStartMs = start, sleepEndMs = end, durationMinutes = night.duration, source = DataSource.MANUAL)
        )
        summaryRepo.upsert(
            DailySummary(
                date = night.date,
                // Verbatim copy of session.durationMinutes, matching DailySummaryWorker's real write path.
                sleepMinutes = night.duration,
                sleepDeepMinutes = night.deep,
                sleepLightMinutes = night.light,
                sleepRemMinutes = night.rem,
                sleepAwakeMinutes = night.awake,
                source = DataSource.MANUAL,
                computedAt = java.time.Instant.now(),
            )
        )
    }

    @Test
    fun `stored-row averages match the live calculator for all 5 migrated metrics, onset wake stay live`() = runBlocking {
        val anchor = LocalDate.of(2026, 7, 10)
        val allMetrics = listOf(BaselineMetric.SLEEP, BaselineMetric.SLEEP_ONSET, BaselineMetric.SLEEP_WAKE) + stageMetrics.map { it.first }
        allMetrics.forEach { windowConfigRepo.setOverride(it, windowDays = 5, minimumDays = 3) }

        val nights = listOf(
            Night(anchor.minusDays(4), duration = 400, deep = 80, light = 200, rem = 90, awake = 30),
            Night(anchor.minusDays(3), duration = 420, deep = 90, light = 210, rem = 90, awake = 30),
            Night(anchor.minusDays(2), duration = 380, deep = 70, light = 190, rem = 90, awake = 30),
            Night(anchor.minusDays(1), duration = 440, deep = 100, light = 220, rem = 90, awake = 30),
            Night(anchor, duration = 410, deep = 85, light = 205, rem = 90, awake = 30),
        )
        nights.forEach { seedNight(it) }

        // Populate metric_daily_stats via the real write-path calculators (as prompt 2's worker would).
        val sleepRow = GenericTrailingStatsCalculator(BaselineMetric.SLEEP, summaryRepo, windowConfigRepo) { it.sleepMinutes?.toDouble() }
            .compute(anchor, zone)
        db.metricDailyStatsDao().upsert(sleepRow)
        stageMetrics.forEach { (metric, selector) ->
            val row = SleepStageStatsCalculator(metric, summaryRepo, windowConfigRepo, selector).compute(anchor, zone)
            db.metricDailyStatsDao().upsert(row)
        }

        val live = sleepAverageCalculator.observe(anchor).first()
        val migrated = provider.observeSleepData(anchor).first()!!.averages

        assertEquals(live.avgTotalMinutes, migrated.avgTotalMinutes)
        assertEquals(live.avgDeepMinutes, migrated.avgDeepMinutes)
        assertEquals(live.avgDeepPct!!, migrated.avgDeepPct!!, 1e-9)
        assertEquals(live.avgLightMinutes, migrated.avgLightMinutes)
        assertEquals(live.avgLightPct!!, migrated.avgLightPct!!, 1e-9)
        assertEquals(live.avgRemMinutes, migrated.avgRemMinutes)
        assertEquals(live.avgRemPct!!, migrated.avgRemPct!!, 1e-9)
        assertEquals(live.avgAwakeMinutes, migrated.avgAwakeMinutes)
        assertEquals(live.avgAwakePct!!, migrated.avgAwakePct!!, 1e-9)

        // Onset/wake have no stored row (CalculatorModule) — must still come from the live calculator.
        assertEquals(live.avgOnsetTime, migrated.avgOnsetTime)
        assertEquals(live.avgWakeTime, migrated.avgWakeTime)
    }

    @Test
    fun `a stale or missing stored row nulls out only its own field, not the whole SleepAverages`() = runBlocking {
        val anchor = LocalDate.of(2026, 7, 10)
        val allMetrics = listOf(BaselineMetric.SLEEP, BaselineMetric.SLEEP_ONSET, BaselineMetric.SLEEP_WAKE) + stageMetrics.map { it.first }
        allMetrics.forEach { windowConfigRepo.setOverride(it, windowDays = 5, minimumDays = 3) }

        val nights = (0..4).map { i ->
            Night(anchor.minusDays((4 - i).toLong()), duration = 400 + i * 10, deep = 80, light = 200, rem = 90, awake = 30)
        }
        nights.forEach { seedNight(it) }

        // Deliberately only populate SLEEP_DEEP's stored row -- SLEEP and the other 3 stage
        // metrics are left unpopulated (as if their write-path row hasn't landed yet).
        val deepRow = SleepStageStatsCalculator(BaselineMetric.SLEEP_DEEP, summaryRepo, windowConfigRepo) { it.sleepDeepMinutes }
            .compute(anchor, zone)
        db.metricDailyStatsDao().upsert(deepRow)

        val migrated = provider.observeSleepData(anchor).first()!!.averages

        assertEquals(80, migrated.avgDeepMinutes)
        assertEquals(null, migrated.avgTotalMinutes)
        assertEquals(null, migrated.avgLightMinutes)
        assertEquals(null, migrated.avgRemMinutes)
        assertEquals(null, migrated.avgAwakeMinutes)
    }
}
