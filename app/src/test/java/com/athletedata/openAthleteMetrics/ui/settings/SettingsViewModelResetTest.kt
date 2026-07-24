package com.athletedata.openAthleteMetrics.ui.settings

import android.app.Application
import androidx.room.Room
import com.athletedata.openAthleteMetrics.ble.BleEngine
import com.athletedata.openAthleteMetrics.ble.driver.DriverRegistry
import com.athletedata.openAthleteMetrics.ble.driver.DriverStorage
import com.athletedata.openAthleteMetrics.data.db.AppDatabase
import com.athletedata.openAthleteMetrics.data.db.MetricDailyStatsEntity
import com.athletedata.openAthleteMetrics.data.model.BaselineMetric
import com.athletedata.openAthleteMetrics.data.model.ThemePreference
import com.athletedata.openAthleteMetrics.data.repository.DeviceRepository
import com.athletedata.openAthleteMetrics.data.repository.SettingsRepository
import com.athletedata.openAthleteMetrics.data.repository.UserProfileRepository
import io.mockk.every
import io.mockk.mockk
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

/**
 * Regression guard for the RESET-SYSTEM repoint: [SettingsViewModel.executeReset] must wipe
 * `metric_daily_stats` (the live stats table), not the now-removed `baseline_range`/`BaselineDao`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SettingsViewModelResetTest {

    private lateinit var db: AppDatabase
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val settingsRepo = mockk<SettingsRepository>(relaxed = true) {
            every { getThemePreference() } returns flowOf(ThemePreference.SYSTEM)
        }
        viewModel = SettingsViewModel(
            settingsRepository = settingsRepo,
            appDatabase = db,
            context = RuntimeEnvironment.getApplication(),
            userProfileRepository = mockk<UserProfileRepository>(relaxed = true),
            deviceRepository = mockk<DeviceRepository>(relaxed = true),
            driverRegistry = mockk<DriverRegistry>(relaxed = true),
            driverStorage = mockk<DriverStorage>(relaxed = true),
            bleEngine = mockk<BleEngine>(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // executeReset() runs its deletion work on Dispatchers.IO -- a real background thread, not a
    // test-scheduler-controlled one -- so completion is observed by polling, matching the
    // established pattern for real-dispatcher async work elsewhere in this test suite (see
    // BleEngineTest.awaitPrivateField).
    private fun awaitNotBusy(timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && viewModel.uiState.value.isBusy) {
            Thread.sleep(10)
        }
    }

    @Test
    fun `executeReset with metrics selected wipes metric_daily_stats`() = runBlocking {
        db.metricDailyStatsDao().upsert(
            MetricDailyStatsEntity(
                summaryDate = LocalDate.of(2026, 1, 1),
                metricType = BaselineMetric.HR,
                mean = 60.0,
                stdDev = 5.0,
                meanPct = null,
                sampleCount = 10,
                windowDays = 30,
                minimumDays = 14,
                configHash = "test-hash",
                computedAt = Instant.now(),
            )
        )
        assertTrue(db.metricDailyStatsDao().getAllForMetricOnce(BaselineMetric.HR).isNotEmpty())

        viewModel.toggleMetrics()
        viewModel.onDeleteSelectedClicked()
        viewModel.onResetConfirmTextChanged("DELETE")
        viewModel.executeReset()
        awaitNotBusy()

        assertEquals(0, db.metricDailyStatsDao().getAllForMetricOnce(BaselineMetric.HR).size)
    }
}
