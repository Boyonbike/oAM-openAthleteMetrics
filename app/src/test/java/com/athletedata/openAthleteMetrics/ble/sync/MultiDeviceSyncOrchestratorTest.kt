package com.athletedata.openAthleteMetrics.ble.sync

import com.athletedata.openAthleteMetrics.ble.BleConnectionState
import com.athletedata.openAthleteMetrics.ble.BleEngine
import com.athletedata.openAthleteMetrics.ble.driver.BleConfig
import com.athletedata.openAthleteMetrics.ble.driver.DriverRegistry
import com.athletedata.openAthleteMetrics.ble.driver.MatchConfidence
import com.athletedata.openAthleteMetrics.ble.driver.ParsingConfig
import com.athletedata.openAthleteMetrics.ble.driver.StepsMode
import com.athletedata.openAthleteMetrics.ble.driver.WasmDriverManifest
import com.athletedata.openAthleteMetrics.ble.driver.WasmExports
import com.athletedata.openAthleteMetrics.data.model.Device
import com.athletedata.openAthleteMetrics.data.model.MetricType
import com.athletedata.openAthleteMetrics.data.repository.DeviceRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure-JVM (mockk-only, no Room/Robolectric), matching [com.athletedata.openAthleteMetrics.ui.devices.DevicesViewModelStarAutoSyncTest]'s
 * style: BleEngine.connectionState is faked with a real MutableStateFlow that
 * connectToDevice()/disconnect()/acknowledgeSyncComplete() stubs push onto synchronously
 * (no coroutine delays needed to drive the state sequence deterministically).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MultiDeviceSyncOrchestratorTest {

    private val events = mutableListOf<String>()
    private val stateFlow = MutableStateFlow<BleConnectionState>(BleConnectionState.Idle)

    private lateinit var bleEngine: BleEngine
    private lateinit var deviceRepository: DeviceRepository
    private lateinit var driverRegistry: DriverRegistry
    private lateinit var orchestrator: MultiDeviceSyncOrchestrator

    private fun testManifest(driverId: String = "test-driver") = WasmDriverManifest(
        id = driverId,
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

    private fun device(id: Long, name: String, primary: Boolean = false) = Device(
        id = id,
        bleAddress = "AA:BB:CC:DD:EE:0$id",
        driverId = "test-driver",
        displayName = name,
        isPrimary = primary,
        autoSyncEnabled = true,
    )

    // Terminal state pushed by connectToDevice() for each address, keyed by address so
    // different devices in the same test can resolve differently.
    private val terminalByAddress = mutableMapOf<String, () -> BleConnectionState>()

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        // MultiDeviceSyncOrchestrator's own scope resolves Dispatchers.Main.immediate at
        // construction time, so Main must be set before building it — matches
        // DevicesViewModelStarAutoSyncTest's convention.
        Dispatchers.setMain(testDispatcher)
        stateFlow.value = BleConnectionState.Idle
        bleEngine = mockk(relaxed = true) {
            every { connectionState } returns stateFlow
            every { connectToDevice(any(), any()) } answers {
                val address = firstArg<String>()
                events += "connect:$address"
                stateFlow.value = BleConnectionState.Connecting(address)
                stateFlow.value = BleConnectionState.Syncing(address, 0)
                val terminal = terminalByAddress[address]?.invoke()
                    ?: BleConnectionState.SyncComplete(mockk(relaxed = true), address)
                stateFlow.value = terminal
            }
            every { acknowledgeSyncComplete() } answers {
                events += "ack"
                stateFlow.value = BleConnectionState.Idle
            }
            every { disconnect() } answers {
                events += "disconnect"
                stateFlow.value = BleConnectionState.Idle
            }
        }
        driverRegistry = mockk(relaxed = true) {
            every { allDrivers() } returns listOf(testManifest())
        }
        deviceRepository = mockk(relaxed = true)
        orchestrator = MultiDeviceSyncOrchestrator(bleEngine, deviceRepository, driverRegistry)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `syncs devices in the order the repository returns them`() = runTest(testDispatcher) {
        val primary = device(1, "Primary", primary = true)
        val secondary = device(2, "Secondary")
        // Ordering (primary-first, auto-sync-enabled only) is the DAO query's job
        // (DeviceDao.getAutoSyncEnabledOrdered) — the orchestrator just has to connect in
        // whatever order the repository hands back, and disabled devices are excluded simply
        // by never appearing in that list.
        coEvery { deviceRepository.getAutoSyncEnabledOrdered() } returns listOf(primary, secondary)

        orchestrator.runSequentialSync()

        assertEquals(listOf(primary.bleAddress, secondary.bleAddress), events.filter { it.startsWith("connect:") }.map { it.removePrefix("connect:") })
    }

    @Test
    fun `never connects the next device before the previous device's cleanup completes`() = runTest(testDispatcher) {
        val a = device(1, "A")
        val b = device(2, "B")
        coEvery { deviceRepository.getAutoSyncEnabledOrdered() } returns listOf(a, b)

        orchestrator.runSequentialSync()

        assertEquals(
            listOf("connect:${a.bleAddress}", "ack", "connect:${b.bleAddress}", "ack"),
            events,
        )
    }

    @Test
    fun `EOS device advances via acknowledgeSyncComplete, not disconnect`() = runTest(testDispatcher) {
        val eosDevice = device(1, "EOS")
        coEvery { deviceRepository.getAutoSyncEnabledOrdered() } returns listOf(eosDevice)
        terminalByAddress[eosDevice.bleAddress] = {
            BleConnectionState.SyncComplete(mockk(relaxed = true), eosDevice.bleAddress)
        }

        orchestrator.runSequentialSync()

        verify { bleEngine.acknowledgeSyncComplete() }
        verify(exactly = 0) { bleEngine.disconnect() }
    }

    @Test
    fun `passive-stream device advances on quiescence via disconnect`() = runTest(testDispatcher) {
        val passiveDevice = device(1, "Passive")
        coEvery { deviceRepository.getAutoSyncEnabledOrdered() } returns listOf(passiveDevice)
        terminalByAddress[passiveDevice.bleAddress] = {
            BleConnectionState.Connected(passiveDevice.bleAddress, "Passive Driver", packetsReceived = 12, isQuiescent = true)
        }

        orchestrator.runSequentialSync()

        verify { bleEngine.disconnect() }
        verify(exactly = 0) { bleEngine.acknowledgeSyncComplete() }
    }

    @Test
    fun `a device failing to sync does not stop the run`() = runTest(testDispatcher) {
        val failing = device(1, "Failing")
        val healthy = device(2, "Healthy")
        coEvery { deviceRepository.getAutoSyncEnabledOrdered() } returns listOf(failing, healthy)
        terminalByAddress[failing.bleAddress] = { BleConnectionState.Error("Bluetooth is disabled") }

        orchestrator.runSequentialSync()

        assertEquals(listOf(failing.bleAddress, healthy.bleAddress), events.filter { it.startsWith("connect:") }.map { it.removePrefix("connect:") })
    }

    @Test
    fun `a no-op disconnect after max-retries-exceeded does not block the run`() = runTest(testDispatcher) {
        val unreachable = device(1, "Unreachable")
        val next = device(2, "Next")
        coEvery { deviceRepository.getAutoSyncEnabledOrdered() } returns listOf(unreachable, next)
        // scheduleRetry()'s max-retries-exceeded path sets Disconnected AND already nulls
        // activeDeviceAddress itself, so BleEngine.disconnect() (whose first line is
        // `val address = activeDeviceAddress ?: return`) is a true no-op afterwards — it must
        // not mutate connectionState at all, mirroring the real engine.
        terminalByAddress[unreachable.bleAddress] = {
            BleConnectionState.Disconnected(unreachable.bleAddress, "Max retries exceeded")
        }
        every { bleEngine.disconnect() } answers { events += "disconnect-noop" }

        orchestrator.runSequentialSync()

        assertTrue("expected settle-wait to be skipped, elapsed=${testScheduler.currentTime}ms", testScheduler.currentTime < 1_000L)
        assertEquals(listOf(unreachable.bleAddress, next.bleAddress), events.filter { it.startsWith("connect:") }.map { it.removePrefix("connect:") })
    }

    @Test
    fun `runIfIdle runs the sync and returns true when idle`() = runTest(testDispatcher) {
        val solo = device(1, "Solo")
        coEvery { deviceRepository.getAutoSyncEnabledOrdered() } returns listOf(solo)

        val ran = orchestrator.runIfIdle()

        assertTrue(ran)
        verify { bleEngine.connectToDevice(solo.bleAddress, any()) }
    }

    @Test
    fun `runIfIdle returns false and never touches the engine when a run is already active`() = runTest(testDispatcher) {
        // Keeps the first runIfIdle() suspended (isRunning already flipped true) so the
        // second, unlaunched call below is guaranteed to observe it as busy — the shared
        // gate MainActivity, BackgroundSyncWorker and CompanionPresenceSyncTrigger all rely on.
        coEvery { deviceRepository.getAutoSyncEnabledOrdered() } coAnswers {
            delay(1_000)
            emptyList()
        }

        val firstRun = launch { orchestrator.runIfIdle() }
        runCurrent() // let firstRun start and suspend on the delay above, past the CAS

        val secondResult = orchestrator.runIfIdle()

        assertFalse(secondResult)
        advanceUntilIdle()
        firstRun.join()
        verify(exactly = 0) { bleEngine.connectToDevice(any(), any()) }
    }
}
