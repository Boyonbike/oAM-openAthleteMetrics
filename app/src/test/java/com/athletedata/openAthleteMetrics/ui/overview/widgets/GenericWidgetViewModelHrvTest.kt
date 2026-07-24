package com.athletedata.openAthleteMetrics.ui.overview.widgets

import com.athletedata.openAthleteMetrics.data.db.MetricDailyStatsDao
import com.athletedata.openAthleteMetrics.data.db.MetricDailyStatsEntity
import com.athletedata.openAthleteMetrics.data.model.BaselineMetric
import com.athletedata.openAthleteMetrics.data.model.DailySummary
import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.data.model.SleepSession
import com.athletedata.openAthleteMetrics.data.model.WidgetDefinition
import com.athletedata.openAthleteMetrics.data.model.WidgetLayoutHint
import com.athletedata.openAthleteMetrics.data.model.WidgetTemplateId
import com.athletedata.openAthleteMetrics.data.repository.ActivityRepository
import com.athletedata.openAthleteMetrics.data.repository.BaselineWindowConfigRepository
import com.athletedata.openAthleteMetrics.data.repository.DailyContextRepository
import com.athletedata.openAthleteMetrics.data.repository.DailySummaryRepository
import com.athletedata.openAthleteMetrics.data.repository.HrvReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.MetricDailyStatsReader
import com.athletedata.openAthleteMetrics.data.repository.MetricStatsConfigHash
import com.athletedata.openAthleteMetrics.data.repository.QuestionRepository
import com.athletedata.openAthleteMetrics.data.repository.SleepDetailProvider
import com.athletedata.openAthleteMetrics.data.repository.SleepRepository
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
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Regression coverage for the same historical-HRV-baseline bug as
 * [com.athletedata.openAthleteMetrics.ui.dailydetail.DailyDetailViewModelHrvTest], for the
 * Generic Widget's HRV card (`GenericWidget.kt:215`'s `hrvFlow`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GenericWidgetViewModelHrvTest {

    private val testDispatcher = StandardTestDispatcher()
    private val windowDays = 30
    private val minimumDays = 14

    private lateinit var dao: MetricDailyStatsDao
    private lateinit var windowConfigRepo: BaselineWindowConfigRepository
    private lateinit var metricDailyStatsReader: MetricDailyStatsReader
    private lateinit var hrvHash: String

    private val hrvWidget = WidgetDefinition(
        id = 1,
        templateId = WidgetTemplateId.HRV,
        layoutHint = WidgetLayoutHint.SINGLE_METRIC,
        colSpan = 1,
        rowSpan = 1,
        sequenceOrder = 0,
        bindings = emptyList(),
    )

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

    private fun buildViewModel(sleepRepo: SleepRepository, summaryRepo: DailySummaryRepository): GenericWidgetViewModel =
        GenericWidgetViewModel(
            summaryRepo = summaryRepo,
            contextRepo = mockk(relaxed = true),
            activityRepo = mockk<ActivityRepository>(relaxed = true),
            questionRepo = mockk<QuestionRepository>(relaxed = true),
            sleepRepo = sleepRepo,
            sleepDetailProvider = mockk<SleepDetailProvider>(relaxed = true) { every { observeSleepData(any()) } returns flowOf(null) },
            metricDailyStatsReader = metricDailyStatsReader,
            hrvRepo = mockk<HrvReadingRepository>(relaxed = true) { coEvery { getReadingsInRangeOnce(any(), any()) } returns emptyList() },
        )

    @Test
    fun `widget shows the viewed date's stored HRV baseline, not today's`() = runTest(testDispatcher) {
        val today = LocalDate.of(2026, 7, 22)
        val pastDay = today.minusDays(10)

        val sleepRepo = mockk<SleepRepository> { every { getSessionForDate(pastDay) } returns flowOf(session(pastDay)) }
        val summaryRepo = mockk<DailySummaryRepository> {
            every { getSummaryForDate(pastDay) } returns flowOf(summary(pastDay, overnightHrvMs = 55.0))
        }
        every { dao.observe(BaselineMetric.HRV, pastDay) } returns flowOf(statsRow(pastDay, mean = 40.0, stdDev = 5.0))

        val vm = buildViewModel(sleepRepo, summaryRepo)
        vm.setup(pastDay, hrvWidget)
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        val state = vm.uiState.value as GenericWidgetUiState.HrvMetric.HasData
        assertEquals(55.0, state.headlineMs, 1e-9)
        assertEquals(35.0, state.baseline!!.lower, 1e-9)
        assertEquals(45.0, state.baseline.upper, 1e-9)
    }

    @Test
    fun `no stored row for the viewed date renders no band`() = runTest(testDispatcher) {
        val pastDay = LocalDate.of(2026, 7, 12)
        val sleepRepo = mockk<SleepRepository> { every { getSessionForDate(pastDay) } returns flowOf(session(pastDay)) }
        val summaryRepo = mockk<DailySummaryRepository> {
            every { getSummaryForDate(pastDay) } returns flowOf(summary(pastDay, overnightHrvMs = 55.0))
        }
        every { dao.observe(BaselineMetric.HRV, pastDay) } returns flowOf(null)

        val vm = buildViewModel(sleepRepo, summaryRepo)
        vm.setup(pastDay, hrvWidget)
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        val state = vm.uiState.value as GenericWidgetUiState.HrvMetric.HasData
        assertEquals(55.0, state.headlineMs, 1e-9)
        assertNull(state.baseline)
    }
}
