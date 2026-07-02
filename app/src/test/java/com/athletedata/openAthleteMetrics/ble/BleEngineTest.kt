package com.athletedata.openAthleteMetrics.ble

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.athletedata.openAthleteMetrics.ble.driver.BleConfig
import com.athletedata.openAthleteMetrics.ble.driver.DriverRegistry
import com.athletedata.openAthleteMetrics.ble.driver.MatchConfidence
import com.athletedata.openAthleteMetrics.ble.driver.ParsingConfig
import com.athletedata.openAthleteMetrics.ble.driver.StepsMode
import com.athletedata.openAthleteMetrics.ble.driver.WasmDriverManifest
import com.athletedata.openAthleteMetrics.ble.driver.WasmExports
import com.athletedata.openAthleteMetrics.ble.sync.DeviceSyncProcessor
import com.athletedata.openAthleteMetrics.ble.sync.MetricRouter
import com.athletedata.openAthleteMetrics.ble.sync.SyncSummary
import com.athletedata.openAthleteMetrics.ble.sync.SyncValidator
import com.athletedata.openAthleteMetrics.ble.sync.ValidationResult
import com.athletedata.openAthleteMetrics.ble.wasm.SessionFrame
import com.athletedata.openAthleteMetrics.ble.wasm.WasmParseResult
import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.data.model.MetricReading
import com.athletedata.openAthleteMetrics.data.model.MetricType
import com.athletedata.openAthleteMetrics.data.repository.DeviceRepository
import com.athletedata.openAthleteMetrics.data.repository.RawDeviceDataRepository
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import junit.framework.TestCase.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class BleEngineTest {

    private lateinit var driverRegistry: DriverRegistry
    private lateinit var syncContextFactory: SyncContextFactory
    private lateinit var syncProcessor: DeviceSyncProcessor
    private lateinit var validator: SyncValidator
    private lateinit var deviceRepository: DeviceRepository
    private lateinit var rawDeviceDataRepository: RawDeviceDataRepository
    private lateinit var metricRouter: MetricRouter
    private lateinit var workManager: WorkManager
    private lateinit var engine: BleEngine

    @Before
    fun setUp() {
        // BleEngine's `scope` field captures Dispatchers.Main.immediate at construction
        // time, so the Main dispatcher must be installed before the engine is built.
        Dispatchers.setMain(UnconfinedTestDispatcher())

        driverRegistry = mockk(relaxed = true)
        syncContextFactory = mockk(relaxed = true)
        syncProcessor = mockk(relaxed = true)
        validator = mockk(relaxed = true)
        deviceRepository = mockk(relaxed = true)
        rawDeviceDataRepository = mockk(relaxed = true)
        metricRouter = mockk(relaxed = true)
        workManager = mockk(relaxed = true)

        engine = BleEngine(
            context = mockk<Context>(relaxed = true),
            driverRegistry = driverRegistry,
            syncContextFactory = syncContextFactory,
            syncProcessor = syncProcessor,
            validator = validator,
            deviceRepository = deviceRepository,
            rawDeviceDataRepository = rawDeviceDataRepository,
            metricRouter = metricRouter,
            workManager = workManager,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
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

    private fun setPrivateField(name: String, value: Any?) {
        val field = BleEngine::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(engine, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun addSessionFrame(frame: SessionFrame) {
        val field = BleEngine::class.java.getDeclaredField("sessionCache")
        field.isAccessible = true
        (field.get(engine) as MutableList<SessionFrame>).add(frame)
    }

    private fun invokeDispatchPostStreamParse() {
        val method = BleEngine::class.java.getDeclaredMethod("dispatchPostStreamParse")
        method.isAccessible = true
        method.invoke(engine)
    }

    @Test
    fun `dispatchPostStreamParse enqueues the summary worker only after syncProcessor process completes`() {
        val reading = MetricReading(
            metricType = MetricType.HR,
            value = 60.0,
            unit = "bpm",
            recordedAt = Instant.parse("2026-06-15T12:00:00Z"),
            createdAt = Instant.now(),
            source = DataSource.DEVICE,
            driverId = "test-driver",
        )
        coEvery { driverRegistry.parseSession(any(), any()) } returns WasmParseResult.Success(listOf(reading))
        every { validator.validateReadings(any()) } returns listOf(ValidationResult.Accepted(reading))
        coEvery { syncProcessor.process(any(), any()) } returns mockk<SyncSummary>(relaxed = true)

        setPrivateField("activeManifest", testManifest())
        setPrivateField("activeDeviceAddress", "AA:BB:CC:DD:EE:FF")
        addSessionFrame(SessionFrame("notify", "0x01", byteArrayOf(1, 2, 3)))

        val enqueueLatch = CountDownLatch(1)
        every {
            workManager.enqueueUniqueWork(any(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        } answers {
            enqueueLatch.countDown()
            mockk(relaxed = true)
        }

        invokeDispatchPostStreamParse()

        assertTrue("enqueueUniqueWork was not called", enqueueLatch.await(5, TimeUnit.SECONDS))
        coVerifyOrder {
            syncProcessor.process(any(), any())
            workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>())
        }
    }
}
