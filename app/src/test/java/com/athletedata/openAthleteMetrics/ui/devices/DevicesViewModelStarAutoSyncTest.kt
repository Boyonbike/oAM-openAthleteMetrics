package com.athletedata.openAthleteMetrics.ui.devices

import com.athletedata.openAthleteMetrics.ble.BleConnectionState
import com.athletedata.openAthleteMetrics.ble.BleEngine
import com.athletedata.openAthleteMetrics.ble.companion.CompanionDeviceAssociator
import com.athletedata.openAthleteMetrics.ble.driver.DriverRegistry
import com.athletedata.openAthleteMetrics.ble.driver.DriverStorage
import com.athletedata.openAthleteMetrics.ble.sync.DeviceReprocessor
import com.athletedata.openAthleteMetrics.data.model.Device
import com.athletedata.openAthleteMetrics.data.repository.DeviceRepository
import com.athletedata.openAthleteMetrics.data.repository.SettingsRepository
import com.athletedata.openAthleteMetrics.data.repository.SyncSessionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure-JVM (mockk-only, no Room/Robolectric) since all of [DevicesViewModel]'s dependencies are
 * plain interfaces/classes — matches [com.athletedata.openAthleteMetrics.ui.dailydetail.DailyDetailViewModelHrvTest]'s style.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DevicesViewModelStarAutoSyncTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var deviceRepository: DeviceRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var viewModel: DevicesViewModel

    private fun buildViewModel() {
        viewModel = DevicesViewModel(
            deviceRepository = deviceRepository,
            driverStorage = mockk<DriverStorage>(relaxed = true),
            driverRegistry = mockk<DriverRegistry>(relaxed = true) {
                every { allDrivers() } returns emptyList()
            },
            bleEngine = mockk<BleEngine>(relaxed = true) {
                every { connectionState } returns MutableStateFlow(BleConnectionState.Idle)
                every { discoveredCandidates } returns MutableStateFlow(emptyList())
            },
            deviceReprocessor = mockk<DeviceReprocessor>(relaxed = true),
            syncSessionRepository = mockk<SyncSessionRepository>(relaxed = true) {
                coEvery { getRecentInProgress(any()) } returns emptyList()
            },
            settingsRepository = settingsRepository,
            companionDeviceAssociator = mockk<CompanionDeviceAssociator>(relaxed = true),
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        deviceRepository = mockk(relaxed = true) {
            every { getAllDevices() } returns flowOf(emptyList())
        }
        settingsRepository = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun nonPrimaryDevice() = Device(
        id = 1L,
        bleAddress = "AA:BB:CC:DD:EE:FF",
        driverId = "hume_band_v1",
        displayName = "Test Band",
        isPrimary = false,
    )

    @Test
    fun `onStarTapped on non-primary device calls setPrimary`() = runTest(testDispatcher) {
        buildViewModel()
        val device = nonPrimaryDevice()

        viewModel.onStarTapped(device)
        advanceUntilIdle()

        coVerify { deviceRepository.setPrimary(device.id) }
    }

    @Test
    fun `onStarTapped on already-primary device is a no-op`() = runTest(testDispatcher) {
        buildViewModel()
        val device = nonPrimaryDevice().copy(isPrimary = true)

        viewModel.onStarTapped(device)
        advanceUntilIdle()

        coVerify(exactly = 0) { deviceRepository.setPrimary(any()) }
    }

    @Test
    fun `onAutoSyncToggled calls setAutoSync with correct args`() = runTest(testDispatcher) {
        buildViewModel()
        val device = nonPrimaryDevice()

        viewModel.onAutoSyncToggled(device, false)
        advanceUntilIdle()
        coVerify { deviceRepository.setAutoSync(device.id, false) }

        viewModel.onAutoSyncToggled(device, true)
        advanceUntilIdle()
        coVerify { deviceRepository.setAutoSync(device.id, true) }
    }

    @Test
    fun `restarConfirmDismissed reflects settingsRepository value`() = runTest(testDispatcher) {
        every { settingsRepository.getRestarConfirmDismissed() } returns flowOf(true)
        buildViewModel()

        val job = launch { viewModel.restarConfirmDismissed.collect {} }
        advanceUntilIdle()

        assertTrue(viewModel.restarConfirmDismissed.value)
        job.cancel()
    }

    @Test
    fun `restarConfirmDismissed defaults to false`() = runTest(testDispatcher) {
        every { settingsRepository.getRestarConfirmDismissed() } returns flowOf(false)
        buildViewModel()

        val job = launch { viewModel.restarConfirmDismissed.collect {} }
        advanceUntilIdle()

        assertFalse(viewModel.restarConfirmDismissed.value)
        job.cancel()
    }

    @Test
    fun `onRestarConfirmDismissedChanged persists via settingsRepository`() = runTest(testDispatcher) {
        buildViewModel()

        viewModel.onRestarConfirmDismissedChanged(true)
        advanceUntilIdle()

        coVerify { settingsRepository.setRestarConfirmDismissed(true) }
    }
}
