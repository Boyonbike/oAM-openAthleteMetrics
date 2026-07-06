package com.athletedata.openAthleteMetrics.ui.history

import com.athletedata.openAthleteMetrics.data.model.DailySummary
import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.data.repository.DailyContextRepository
import com.athletedata.openAthleteMetrics.data.repository.DailySummaryRepository
import com.athletedata.openAthleteMetrics.data.repository.QuestionRepository
import com.athletedata.openAthleteMetrics.data.repository.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelHrvTest {

    private lateinit var contextRepo: DailyContextRepository
    private lateinit var questionRepo: QuestionRepository
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var sessionState: HistorySessionState

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        contextRepo = mockk(relaxed = true)
        questionRepo = mockk(relaxed = true) {
            every { getLifestyleQuestions() } returns flowOf(emptyList())
            every { getCustomQuestions() } returns flowOf(emptyList())
        }
        settingsRepo = mockk(relaxed = true)
        sessionState = HistorySessionState()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun summary(
        date: LocalDate,
        overnightHrvMs: Double? = null,
        avgHrvMs: Double? = null,
        avgHrBpm: Double? = null,
        restingHrBpm: Double? = null,
        avgSpo2Pct: Double? = null,
        steps: Int? = null,
        sleepMinutes: Int? = null,
    ) = DailySummary(
        date = date,
        overnightHrvMs = overnightHrvMs,
        avgHrvMs = avgHrvMs,
        avgHrBpm = avgHrBpm,
        restingHrBpm = restingHrBpm,
        avgSpo2Pct = avgSpo2Pct,
        steps = steps,
        sleepMinutes = sleepMinutes,
        source = DataSource.DEVICE,
        computedAt = Instant.now(),
    )

    private fun viewModelWith(summaries: List<DailySummary>): HistoryViewModel {
        val summaryRepo = mockk<DailySummaryRepository> {
            every { getSummariesForRange(any(), any()) } returns flowOf(summaries)
        }
        return HistoryViewModel(sessionState, summaryRepo, contextRepo, questionRepo, settingsRepo)
    }

    @Test
    fun `HRV series uses overnight value unchanged when present`() = runTest {
        val day = LocalDate.of(2026, 7, 1)
        val vm = viewModelWith(listOf(summary(day, overnightHrvMs = 55.0, avgHrvMs = 70.0)))
        vm.addMetric("HRV")
        backgroundScope.launch { vm.seriesList.collect {} }
        advanceUntilIdle()

        val entries = vm.seriesList.value.first { it.metricKey == "HRV" }.entries
        assertEquals(1, entries.size)
        assertEquals(55f, entries.single().value)
        assertEquals(day, entries.single().date)
    }

    @Test
    fun `HRV day with null overnight value is dropped, not backfilled from avg`() = runTest {
        val missingDay = LocalDate.of(2026, 7, 1)
        val presentDay = LocalDate.of(2026, 7, 2)
        val vm = viewModelWith(
            listOf(
                summary(missingDay, overnightHrvMs = null, avgHrvMs = 70.0),
                summary(presentDay, overnightHrvMs = 55.0, avgHrvMs = 70.0),
            )
        )
        vm.addMetric("HRV")
        backgroundScope.launch { vm.seriesList.collect {} }
        advanceUntilIdle()

        val entries = vm.seriesList.value.first { it.metricKey == "HRV" }.entries
        // Missing day is dropped from the series entirely -- the same
        // convention fetchSeriesFlow's mapNotNull already applies to
        // HR/RHR/SpO2/Steps/Sleep -- rather than backfilled from avgHrvMs.
        assertEquals(listOf(presentDay), entries.map { it.date })
        assertNull(entries.find { it.date == missingDay })
    }

    @Test
    fun `other metric extraction paths are unchanged`() = runTest {
        val day = LocalDate.of(2026, 7, 1)
        val s = summary(
            date = day,
            avgHrBpm = 60.0,
            restingHrBpm = 50.0,
            avgSpo2Pct = 97.0,
            steps = 8000,
            sleepMinutes = 420,
        )
        val vm = viewModelWith(listOf(s))
        listOf("HR", "RHR", "SPO2", "STEPS", "SLEEP").forEach { vm.addMetric(it) }
        backgroundScope.launch { vm.seriesList.collect {} }
        advanceUntilIdle()

        val series = vm.seriesList.value.associateBy { it.metricKey }
        assertEquals(60f, series.getValue("HR").entries.single().value)
        assertEquals(50f, series.getValue("RHR").entries.single().value)
        assertEquals(97f, series.getValue("SPO2").entries.single().value)
        assertEquals(8000f, series.getValue("STEPS").entries.single().value)
        assertEquals(7f, series.getValue("SLEEP").entries.single().value) // 420 min -> hours
    }
}
