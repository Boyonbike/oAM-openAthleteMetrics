package com.athletedata.openAthleteMetrics.ui.devices

import android.content.IntentSender
import com.athletedata.openAthleteMetrics.ble.BleConnectionState
import com.athletedata.openAthleteMetrics.ble.BleEngine
import com.athletedata.openAthleteMetrics.ble.DiscoveredCandidate
import com.athletedata.openAthleteMetrics.ble.companion.CompanionDeviceAssociator
import com.athletedata.openAthleteMetrics.ble.driver.BleConfig
import com.athletedata.openAthleteMetrics.ble.driver.DriverRegistry
import com.athletedata.openAthleteMetrics.ble.driver.DriverStorage
import com.athletedata.openAthleteMetrics.ble.driver.MatchConfidence
import com.athletedata.openAthleteMetrics.ble.driver.ParsingConfig
import com.athletedata.openAthleteMetrics.ble.driver.StepsMode
import com.athletedata.openAthleteMetrics.ble.driver.WasmDriverManifest
import com.athletedata.openAthleteMetrics.ble.driver.WasmExports
import com.athletedata.openAthleteMetrics.ble.sync.DeviceReprocessor
import com.athletedata.openAthleteMetrics.ble.sync.MultiDeviceSyncOrchestrator
import com.athletedata.openAthleteMetrics.data.model.MetricType
import com.athletedata.openAthleteMetrics.data.repository.DeviceRepository
import com.athletedata.openAthleteMetrics.data.repository.SettingsRepository
import com.athletedata.openAthleteMetrics.data.repository.SyncSessionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Pure-JVM (mockk-only, no Room/Robolectric), matching [DevicesViewModelStarAutoSyncTest]'s
 * style, for the CDM association wiring added alongside BackgroundSyncWorker.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DevicesViewModelAssociationTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var deviceRepository: DeviceRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var bleEngine: BleEngine
    private lateinit var companionDeviceAssociator: CompanionDeviceAssociator
    private lateinit var viewModel: DevicesViewModel

    private fun buildViewModel() {
        viewModel = DevicesViewModel(
            deviceRepository = deviceRepository,
            driverStorage = mockk<DriverStorage>(relaxed = true),
            driverRegistry = mockk<DriverRegistry>(relaxed = true) {
                every { allDrivers() } returns emptyList()
            },
            bleEngine = bleEngine,
            multiDeviceSyncOrchestrator = mockk<MultiDeviceSyncOrchestrator>(relaxed = true),
            deviceReprocessor = mockk<DeviceReprocessor>(relaxed = true),
            syncSessionRepository = mockk<SyncSessionRepository>(relaxed = true) {
                coEvery { getRecentInProgress(any()) } returns emptyList()
            },
            settingsRepository = settingsRepository,
            companionDeviceAssociator = companionDeviceAssociator,
        )
    }

    private fun testManifest() = WasmDriverManifest(
        id = "test-driver",
        displayName = "Test",
        version = "1.0.0",
        author = "test",
        supportedMetrics = listOf(MetricType.HR),
        ble = BleConfig(
            matchConfidence = MatchConfidence.CERTAIN,
            services = listOf("0000180d-0000-1000-8000-00805f9b34fb"),
            characteristics = mapOf(
                "write" to "00002a39-0000-1000-8000-00805f9b34fb",
                "notify" to "00002a37-0000-1000-8000-00805f9b34fb",
            ),
        ),
        parsing = ParsingConfig.WasmParsing(
            wasmBytes = ByteArray(0),
            exports = WasmExports(parseMetrics = "parseMetrics"),
        ),
        stepsMode = StepsMode.DELTA,
    )

    private fun candidate(address: String = "AA:BB:CC:DD:EE:FF") = DiscoveredCandidate(
        address = address,
        manifest = testManifest(),
        deviceName = "Test Band",
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        deviceRepository = mockk(relaxed = true) {
            every { getAllDevices() } returns flowOf(emptyList())
        }
        settingsRepository = mockk(relaxed = true)
        bleEngine = mockk(relaxed = true) {
            every { connectionState } returns MutableStateFlow(BleConnectionState.Idle)
            every { discoveredCandidates } returns MutableStateFlow(emptyList())
            every { connectToCandidate(any()) } returns true
        }
        companionDeviceAssociator = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `onCandidateSelected connects regardless of a pending association request`() = runTest(testDispatcher) {
        coEvery { companionDeviceAssociator.requestAssociation(any(), any()) } returns mockk<IntentSender>(relaxed = true)
        buildViewModel()
        val candidate = candidate()

        viewModel.onCandidateSelected(candidate)
        advanceUntilIdle()

        verify { bleEngine.connectToCandidate(candidate) }
    }

    @Test
    fun `onCandidateSelected connects even when CDM is unavailable`() = runTest(testDispatcher) {
        coEvery { companionDeviceAssociator.requestAssociation(any(), any()) } returns null
        buildViewModel()
        val candidate = candidate()

        viewModel.onCandidateSelected(candidate)
        advanceUntilIdle()

        verify { bleEngine.connectToCandidate(candidate) }
    }

    @Test
    fun `declined association persists cdm_associated = false and does not arm presence observation`() = runTest(testDispatcher) {
        buildViewModel()

        viewModel.onAssociationResult("AA:BB:CC:DD:EE:FF", granted = false)
        advanceUntilIdle()

        coVerify { deviceRepository.setCdmAssociated("AA:BB:CC:DD:EE:FF", false) }
        verify(exactly = 0) { companionDeviceAssociator.startObservingPresence(any()) }
    }

    @Test
    fun `granted association persists cdm_associated = true and arms presence observation`() = runTest(testDispatcher) {
        buildViewModel()

        viewModel.onAssociationResult("AA:BB:CC:DD:EE:FF", granted = true)
        advanceUntilIdle()

        coVerify { deviceRepository.setCdmAssociated("AA:BB:CC:DD:EE:FF", true) }
        verify { companionDeviceAssociator.startObservingPresence("AA:BB:CC:DD:EE:FF") }
    }
}
