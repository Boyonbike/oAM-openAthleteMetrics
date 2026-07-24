package com.athletedata.openAthleteMetrics.ui.dailydetail

import com.athletedata.openAthleteMetrics.data.db.MetricDailyStatsDao
import com.athletedata.openAthleteMetrics.data.db.MetricDailyStatsEntity
import com.athletedata.openAthleteMetrics.data.model.BaselineMetric
import com.athletedata.openAthleteMetrics.data.model.DailySummary
import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.data.model.SleepSession
import com.athletedata.openAthleteMetrics.data.repository.ActiveCalorieReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.ActivityRepository
import com.athletedata.openAthleteMetrics.data.repository.BaselineWindowConfigRepository
import com.athletedata.openAthleteMetrics.data.repository.DailyContextRepository
import com.athletedata.openAthleteMetrics.data.repository.DailySummaryRepository
import com.athletedata.openAthleteMetrics.data.repository.HrReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.HrvReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.MetricDailyStatsReader
import com.athletedata.openAthleteMetrics.data.repository.MetricStatsConfigHash
import com.athletedata.openAthleteMetrics.data.repository.QuestionRepository
import com.athletedata.openAthleteMetrics.data.repository.RespirationReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.SettingsRepository
import com.athletedata.openAthleteMetrics.data.repository.SleepDetailProvider
import com.athletedata.openAthleteMetrics.data.repository.SleepRepository
import com.athletedata.openAthleteMetrics.data.repository.SpO2ReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.StepsReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.TotalCalorieReadingRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Regression coverage for the historical-HRV-baseline bug: [DailyDetailViewModel.uiState]'s
 * `hrv` section must reflect the *viewed* date's stored `metric_daily_stats` row, not always
 * "today's" row (the old [com.athletedata.openAthleteMetrics.data.repository.BaselineRepository.observeRange]
 * behavior, which had no date column at all).
 *
 * Pure-JVM (mockk-only, no Room/Robolectric) since [MetricDailyStatsReader]'s dependencies are
 * plain interfaces — matches [com.athletedata.openAthleteMetrics.ui.history.HistoryViewModelHrvTest]'s style.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DailyDetailViewModelHrvTest {

    private val testDispatcher = StandardTestDispatcher()
    private val windowDays = 30
    private val minimumDays = 14

    private lateinit var dao: MetricDailyStatsDao
    private lateinit var windowConfigRepo: BaselineWindowConfigRepository
    private lateinit var metricDailyStatsReader: MetricDailyStatsReader
    private lateinit var hrvHash: String

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        windowConfigRepo = mockk {
            coEvery { getEffectiveWindow(any()) } returns windowDays
            coEvery { getEffectiveMinimum(any()) } returns minimumDays
            every { observeOverride(any()) } returns flowOf(null)
        }
        dao = mockk()
        metricDailyStatsReader = MetricDailyStatsReader(dao, windowConfigRepo)
        hrvHash = runBlocking { MetricStatsConfigHash.compute(BaselineMetric.HRV, windowDays, minimumDays, windowConfigRepo) }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun statsRow(date: LocalDate, mean: Double, stdDev: Double) = MetricDailyStatsEntity(
        summaryDate = date,
        metricType = BaselineMetric.HRV,
        mean = mean,
        stdDev = stdDev,
        meanPct = null,
        sampleCount = 20,
        windowDays = windowDays,
        minimumDays = minimumDays,
        configHash = hrvHash,
        computedAt = Instant.now(),
    )

    private fun session(date: LocalDate) = SleepSession(
        date = date,
        sleepStartMs = date.minusDays(1).atTime(23, 0).atZone(ZoneId.systemDefault()).toInstant(),
        sleepEndMs = date.atTime(7, 0).atZone(ZoneId.systemDefault()).toInstant(),
        durationMinutes = 480,
        source = DataSource.MANUAL,
    )

    private fun summary(date: LocalDate, overnightHrvMs: Double) =
        DailySummary(date = date, overnightHrvMs = overnightHrvMs, source = DataSource.MANUAL, computedAt = Instant.now())

    private fun buildViewModel(
        sleepRepo: SleepRepository,
        summaryRepo: DailySummaryRepository,
        hrvRepo: HrvReadingRepository,
    ): DailyDetailViewModel {
        return DailyDetailViewModel(
            summaryRepo = summaryRepo,
            contextRepo = mockk(relaxed = true) { every { getForDate(any()) } returns flowOf(null) },
            activityRepo = mockk(relaxed = true) { every { getActivitiesForDate(any()) } returns flowOf(emptyList()) },
            questionRepo = mockk(relaxed = true) {
                every { getResponsesForDate(any()) } returns flowOf(emptyList())
                every { getLifestyleQuestions() } returns flowOf(emptyList())
                every { getCustomQuestions() } returns flowOf(emptyList())
            },
            sleepRepo = sleepRepo,
            sleepDetailProvider = mockk(relaxed = true) { every { observeSleepData(any()) } returns flowOf(null) },
            metricDailyStatsReader = metricDailyStatsReader,
            hrRepo = mockk<HrReadingRepository>(relaxed = true) { coEvery { getReadingsInRangeOnce(any(), any()) } returns emptyList() },
            hrvRepo = hrvRepo,
            spo2Repo = mockk<SpO2ReadingRepository>(relaxed = true) { coEvery { getReadingsInRangeOnce(any(), any()) } returns emptyList() },
            respirationRepo = mockk<RespirationReadingRepository>(relaxed = true) { coEvery { getReadingsInRangeOnce(any(), any()) } returns emptyList() },
            stepsRepo = mockk<StepsReadingRepository>(relaxed = true) { coEvery { getReadingsInRangeOnce(any(), any()) } returns emptyList() },
            activeCalRepo = mockk<ActiveCalorieReadingRepository>(relaxed = true) { coEvery { getReadingsInRangeOnce(any(), any()) } returns emptyList() },
            totalCalRepo = mockk<TotalCalorieReadingRepository>(relaxed = true) { coEvery { getReadingsInRangeOnce(any(), any()) } returns emptyList() },
            settingsRepo = mockk(relaxed = true) { every { getDailyDetailTileConfig() } returns flowOf(emptyList()) },
        )
    }

    @Test
    fun `scrolling to a historical day shows that day's stored HRV baseline, not today's`() = runTest(testDispatcher) {
        val today = LocalDate.of(2026, 7, 22)
        val pastDay = today.minusDays(10)

        val sleepRepo = mockk<SleepRepository> {
            every { getSessionForDate(pastDay) } returns flowOf(session(pastDay))
        }
        val summaryRepo = mockk<DailySummaryRepository> {
            every { getSummaryForDate(pastDay) } returns flowOf(summary(pastDay, overnightHrvMs = 55.0))
        }
        every { dao.observe(BaselineMetric.HRV, pastDay) } returns flowOf(statsRow(pastDay, mean = 40.0, stdDev = 5.0))
        val hrvRepo = mockk<HrvReadingRepository>(relaxed = true) { coEvery { getReadingsInRangeOnce(any(), any()) } returns emptyList() }

        val vm = buildViewModel(sleepRepo, summaryRepo, hrvRepo)
        vm.setDate(pastDay)
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        val state = vm.uiState.value as DailyDetailUiState.Success
        val hrv = state.hrv as HrvSectionState.HasData
        assertEquals(55.0, hrv.headlineMs, 1e-9)
        assertEquals(35.0, hrv.baseline!!.lower, 1e-9)
        assertEquals(45.0, hrv.baseline.upper, 1e-9)
    }

    @Test
    fun `a day with no stored baseline row renders no band, not a zero band`() = runTest(testDispatcher) {
        val pastDay = LocalDate.of(2026, 7, 12)

        val sleepRepo = mockk<SleepRepository> { every { getSessionForDate(pastDay) } returns flowOf(session(pastDay)) }
        val summaryRepo = mockk<DailySummaryRepository> {
            every { getSummaryForDate(pastDay) } returns flowOf(summary(pastDay, overnightHrvMs = 55.0))
        }
        every { dao.observe(BaselineMetric.HRV, pastDay) } returns flowOf(null)
        val hrvRepo = mockk<HrvReadingRepository>(relaxed = true) { coEvery { getReadingsInRangeOnce(any(), any()) } returns emptyList() }

        val vm = buildViewModel(sleepRepo, summaryRepo, hrvRepo)
        vm.setDate(pastDay)
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        val state = vm.uiState.value as DailyDetailUiState.Success
        val hrv = state.hrv as HrvSectionState.HasData
        assertEquals(55.0, hrv.headlineMs, 1e-9)
        assertNull(hrv.baseline)
    }

    @Test
    fun `navigating between days updates the baseline band each time`() = runTest(testDispatcher) {
        val dayOne = LocalDate.of(2026, 7, 15)
        val dayTwo = LocalDate.of(2026, 7, 16)

        val sleepRepo = mockk<SleepRepository> {
            every { getSessionForDate(dayOne) } returns flowOf(session(dayOne))
            every { getSessionForDate(dayTwo) } returns flowOf(session(dayTwo))
        }
        val summaryRepo = mockk<DailySummaryRepository> {
            every { getSummaryForDate(dayOne) } returns flowOf(summary(dayOne, overnightHrvMs = 50.0))
            every { getSummaryForDate(dayTwo) } returns flowOf(summary(dayTwo, overnightHrvMs = 65.0))
        }
        every { dao.observe(BaselineMetric.HRV, dayOne) } returns flowOf(statsRow(dayOne, mean = 50.0, stdDev = 5.0))
        every { dao.observe(BaselineMetric.HRV, dayTwo) } returns flowOf(statsRow(dayTwo, mean = 70.0, stdDev = 8.0))
        val hrvRepo = mockk<HrvReadingRepository>(relaxed = true) { coEvery { getReadingsInRangeOnce(any(), any()) } returns emptyList() }

        val vm = buildViewModel(sleepRepo, summaryRepo, hrvRepo)
        vm.setDate(dayOne)
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        val firstBand = (vm.uiState.value as DailyDetailUiState.Success).hrv as HrvSectionState.HasData
        assertEquals(45.0, firstBand.baseline!!.lower, 1e-9)

        vm.setDate(dayTwo)
        advanceUntilIdle()
        val secondBand = (vm.uiState.value as DailyDetailUiState.Success).hrv as HrvSectionState.HasData
        assertEquals(62.0, secondBand.baseline!!.lower, 1e-9)
        assertTrue(firstBand.baseline.lower != secondBand.baseline.lower)
    }
}
