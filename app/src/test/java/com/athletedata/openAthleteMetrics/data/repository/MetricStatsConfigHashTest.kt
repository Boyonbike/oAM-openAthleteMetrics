package com.athletedata.openAthleteMetrics.data.repository

import android.app.Application
import androidx.room.Room
import com.athletedata.openAthleteMetrics.data.db.AppDatabase
import com.athletedata.openAthleteMetrics.data.model.BaselineMetric
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

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MetricStatsConfigHashTest {

    private lateinit var db: AppDatabase
    private lateinit var windowConfigRepo: BaselineWindowConfigRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val settingsRepo = mockk<SettingsRepository>(relaxed = true) {
            every { getBaselineWindowDays() } returns flowOf(30)
        }
        windowConfigRepo = RoomBaselineWindowConfigRepository(db.baselineWindowConfigDao(), settingsRepo)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `identical resolution inputs produce identical hash`() = runBlocking {
        windowConfigRepo.setOverride(BaselineMetric.HR, windowDays = 10, minimumDays = 5)

        val first = MetricStatsConfigHash.compute(BaselineMetric.HR, windowDays = 10, minimumDays = 5, windowConfigRepo)
        val second = MetricStatsConfigHash.compute(BaselineMetric.HR, windowDays = 10, minimumDays = 5, windowConfigRepo)

        assertEquals(first, second)
    }

    @Test
    fun `changing the resolved window changes the hash`() = runBlocking {
        windowConfigRepo.setOverride(BaselineMetric.HR, windowDays = 10, minimumDays = 5)
        val before = MetricStatsConfigHash.compute(BaselineMetric.HR, windowDays = 10, minimumDays = 5, windowConfigRepo)

        windowConfigRepo.setOverride(BaselineMetric.HR, windowDays = 20, minimumDays = 5)
        val after = MetricStatsConfigHash.compute(BaselineMetric.HR, windowDays = 20, minimumDays = 5, windowConfigRepo)

        assertNotEquals(before, after)
    }

    @Test
    fun `changing the resolved minimum changes the hash`() = runBlocking {
        windowConfigRepo.setOverride(BaselineMetric.HR, windowDays = 10, minimumDays = 5)
        val before = MetricStatsConfigHash.compute(BaselineMetric.HR, windowDays = 10, minimumDays = 5, windowConfigRepo)

        windowConfigRepo.setOverride(BaselineMetric.HR, windowDays = 10, minimumDays = 8)
        val after = MetricStatsConfigHash.compute(BaselineMetric.HR, windowDays = 10, minimumDays = 8, windowConfigRepo)

        assertNotEquals(before, after)
    }

    @Test
    fun `different metrics resolving to the same pair and tier share a hash by design`() = runBlocking {
        windowConfigRepo.setOverride(BaselineMetric.HR, windowDays = 10, minimumDays = 5)
        windowConfigRepo.setOverride(BaselineMetric.RHR, windowDays = 10, minimumDays = 5)

        val hrHash = MetricStatsConfigHash.compute(BaselineMetric.HR, windowDays = 10, minimumDays = 5, windowConfigRepo)
        val rhrHash = MetricStatsConfigHash.compute(BaselineMetric.RHR, windowDays = 10, minimumDays = 5, windowConfigRepo)

        // Intentional: the composite (summary_date, metric_type) primary key already disambiguates
        // by metric, so the hash's only job is detecting a given metric's own resolution changing.
        assertEquals(hrHash, rhrHash)
    }

    @Test
    fun `same numeric pair but different tiers changes the hash`() = runBlocking {
        // HR has no BaselineFallbackDefaults entry, so with no override it resolves via settings (30) / constant (14).
        val defaultTierHash = MetricStatsConfigHash.compute(BaselineMetric.HR, windowDays = 30, minimumDays = 14, windowConfigRepo)

        // Force the same numeric values via an explicit override -- now the tier is OVERRIDE, not DEFAULT.
        windowConfigRepo.setOverride(BaselineMetric.HR, windowDays = 30, minimumDays = 14)
        val overrideTierHash = MetricStatsConfigHash.compute(BaselineMetric.HR, windowDays = 30, minimumDays = 14, windowConfigRepo)

        assertNotEquals(defaultTierHash, overrideTierHash)
    }
}
