package com.athletedata.openAthleteMetrics.data.repository

import android.app.Application
import androidx.room.Room
import com.athletedata.openAthleteMetrics.data.db.AppDatabase
import com.athletedata.openAthleteMetrics.data.db.MetricDailyStatsEntity
import com.athletedata.openAthleteMetrics.data.model.BaselineMetric
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate

/**
 * Covers the two behaviors [MetricDailyStatsReader] adds on top of a plain DAO read: resolving
 * per-date (not "latest") rows, and filtering out rows whose config_hash no longer matches the
 * metric's currently-resolved window/minimum.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MetricDailyStatsReaderTest {

    private lateinit var db: AppDatabase
    private lateinit var windowConfigRepo: BaselineWindowConfigRepository
    private lateinit var reader: MetricDailyStatsReader

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val settingsRepo = mockk<SettingsRepository>(relaxed = true) {
            every { getBaselineWindowDays() } returns flowOf(30)
        }
        windowConfigRepo = RoomBaselineWindowConfigRepository(db.baselineWindowConfigDao(), settingsRepo)
        reader = MetricDailyStatsReader(db.metricDailyStatsDao(), windowConfigRepo)
        // Pin HRV's resolution explicitly (its BaselineFallbackDefaults entry is 60/15, not the
        // 30/14 used below) so this test doesn't silently drift if that default ever changes.
        runBlocking { windowConfigRepo.setOverride(BaselineMetric.HRV, windowDays = 30, minimumDays = 14) }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun currentHash(metric: BaselineMetric, windowDays: Int, minimumDays: Int) =
        MetricStatsConfigHash.compute(metric, windowDays, minimumDays, windowConfigRepo)

    private suspend fun insertRow(
        date: LocalDate,
        metric: BaselineMetric = BaselineMetric.HRV,
        mean: Double?,
        stdDev: Double?,
        windowDays: Int = 30,
        minimumDays: Int = 14,
        configHash: String? = null,
    ) {
        db.metricDailyStatsDao().upsert(
            MetricDailyStatsEntity(
                summaryDate = date,
                metricType = metric,
                mean = mean,
                stdDev = stdDev,
                meanPct = null,
                sampleCount = 20,
                windowDays = windowDays,
                minimumDays = minimumDays,
                configHash = configHash ?: currentHash(metric, windowDays, minimumDays),
                computedAt = Instant.now(),
            )
        )
    }

    @Test
    fun `returns the band for the requested date, not the latest row`() = runBlocking {
        val dayOne = LocalDate.of(2026, 7, 1)
        val dayTwo = LocalDate.of(2026, 7, 2)
        insertRow(dayOne, mean = 50.0, stdDev = 5.0)
        insertRow(dayTwo, mean = 80.0, stdDev = 10.0)

        val bandOne = reader.observeBaselineRange(BaselineMetric.HRV, dayOne).first()
        val bandTwo = reader.observeBaselineRange(BaselineMetric.HRV, dayTwo).first()

        assertEquals(45.0, bandOne!!.lower, 1e-9)
        assertEquals(55.0, bandOne.upper, 1e-9)
        assertEquals(70.0, bandTwo!!.lower, 1e-9)
        assertEquals(90.0, bandTwo.upper, 1e-9)
    }

    @Test
    fun `no stored row for the date yields a null band, not a zero band`() = runBlocking {
        val band = reader.observeBaselineRange(BaselineMetric.HRV, LocalDate.of(2026, 7, 1)).first()
        assertNull(band)
    }

    @Test
    fun `a row with null mean or stdDev (insufficient window data) yields a null band`() = runBlocking {
        val date = LocalDate.of(2026, 7, 1)
        insertRow(date, mean = null, stdDev = null)

        val band = reader.observeBaselineRange(BaselineMetric.HRV, date).first()
        assertNull(band)
    }

    @Test
    fun `a row whose config_hash no longer matches the current resolution is treated as stale`() = runBlocking {
        val date = LocalDate.of(2026, 7, 1)
        // Row computed under a since-changed window (stale hash), even though mean/stdDev are present.
        insertRow(date, mean = 50.0, stdDev = 5.0, windowDays = 30, minimumDays = 14, configHash = "stale-hash")

        val band = reader.observeBaselineRange(BaselineMetric.HRV, date).first()
        assertNull(band)
    }

    @Test
    fun `a row whose config_hash still matches the current resolution is not stale`() = runBlocking {
        val date = LocalDate.of(2026, 7, 1)
        insertRow(date, mean = 50.0, stdDev = 5.0, windowDays = 30, minimumDays = 14)

        val band = reader.observeBaselineRange(BaselineMetric.HRV, date).first()
        assertEquals(45.0, band!!.lower, 1e-9)
        assertEquals(55.0, band.upper, 1e-9)
    }
}
