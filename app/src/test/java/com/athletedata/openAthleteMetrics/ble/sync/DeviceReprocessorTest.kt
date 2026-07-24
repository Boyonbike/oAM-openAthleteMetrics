package com.athletedata.openAthleteMetrics.ble.sync

import android.app.Application
import androidx.room.Room
import androidx.work.WorkManager
import com.athletedata.openAthleteMetrics.ble.driver.BleConfig
import com.athletedata.openAthleteMetrics.ble.driver.DriverRegistry
import com.athletedata.openAthleteMetrics.ble.driver.MatchConfidence
import com.athletedata.openAthleteMetrics.ble.driver.ParsingConfig
import com.athletedata.openAthleteMetrics.ble.driver.StepsMode
import com.athletedata.openAthleteMetrics.ble.driver.WasmDriverManifest
import com.athletedata.openAthleteMetrics.ble.driver.WasmExports
import com.athletedata.openAthleteMetrics.ble.wasm.WasmParseResult
import com.athletedata.openAthleteMetrics.data.db.AppDatabase
import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.data.model.Device
import com.athletedata.openAthleteMetrics.data.model.MetricReading
import com.athletedata.openAthleteMetrics.data.model.MetricType
import com.athletedata.openAthleteMetrics.data.model.RawPayload
import com.athletedata.openAthleteMetrics.data.repository.ActivityRepository
import com.athletedata.openAthleteMetrics.data.repository.MetricStatsBackfillCoordinator
import com.athletedata.openAthleteMetrics.data.repository.RawDeviceDataRepository
import com.athletedata.openAthleteMetrics.data.repository.SleepRepository
import com.athletedata.openAthleteMetrics.data.repository.SyncSessionRepository
import com.athletedata.openAthleteMetrics.worker.SleepStagePromoter
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Uses a real in-memory Room database only to satisfy AppDatabase.withTransaction (an
 * extension function requiring a real RoomDatabase); readings/sleep/activity persistence
 * itself is mocked since the assertion here is about deviceId being stamped onto the
 * arguments passed to each write call, not about the write's own SQL behavior (covered by
 * the migration/writer tests elsewhere).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DeviceReprocessorTest {

    private lateinit var db: AppDatabase
    private lateinit var driverRegistry: DriverRegistry
    private lateinit var rawDeviceDataRepository: RawDeviceDataRepository
    private lateinit var syncSessionRepository: SyncSessionRepository
    private lateinit var metricRouter: MetricRouter
    private lateinit var sleepRepository: SleepRepository
    private lateinit var activityRepository: ActivityRepository
    private lateinit var workManager: WorkManager
    private lateinit var sleepStagePromoter: SleepStagePromoter
    private lateinit var metricStatsBackfillCoordinator: MetricStatsBackfillCoordinator
    private lateinit var reprocessor: DeviceReprocessor

    private val device = Device(
        id = 88L,
        bleAddress = "AA:BB:CC:DD:EE:FF",
        driverId = "test-driver",
        displayName = "Test",
    )

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

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        driverRegistry = mockk(relaxed = true)
        rawDeviceDataRepository = mockk(relaxed = true)
        syncSessionRepository = mockk(relaxed = true)
        metricRouter = mockk(relaxed = true)
        sleepRepository = mockk(relaxed = true)
        activityRepository = mockk(relaxed = true)
        workManager = mockk(relaxed = true)
        sleepStagePromoter = mockk(relaxed = true)
        metricStatsBackfillCoordinator = mockk(relaxed = true)

        every { driverRegistry.allDrivers() } returns listOf(testManifest())
        coEvery { rawDeviceDataRepository.getForDevice(device.id, any()) } returns listOf(
            RawPayload(
                characteristicUuid = "00002a37-0000-1000-8000-00805f9b34fb",
                payload = byteArrayOf(0x01, 0x02, 0x03),
                receivedAt = Instant.now(),
            )
        )
        coEvery { syncSessionRepository.insert(any()) } returns 1L

        reprocessor = DeviceReprocessor(
            appDatabase = db,
            driverRegistry = driverRegistry,
            rawDeviceDataRepository = rawDeviceDataRepository,
            syncSessionRepository = syncSessionRepository,
            metricRouter = metricRouter,
            sleepRepository = sleepRepository,
            activityRepository = activityRepository,
            validator = SyncValidator(),
            workManager = workManager,
            sleepStagePromoter = sleepStagePromoter,
            metricStatsBackfillCoordinator = metricStatsBackfillCoordinator,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `reprocess stamps the device id onto readings passed to routeAllForceReplace`() = runBlocking {
        val reading = MetricReading(
            metricType = MetricType.HR,
            value = 60.0,
            unit = "bpm",
            recordedAt = Instant.parse("2026-07-01T12:00:00Z"),
            createdAt = Instant.now(),
            source = DataSource.DEVICE,
            driverId = device.driverId,
        )
        coEvery { driverRegistry.parseSession(any(), any()) } returns WasmParseResult.Success(listOf(reading))

        val captured = slot<List<MetricReading>>()
        coEvery {
            metricRouter.routeAllForceReplace(capture(captured), any(), any(), any())
        } just Runs

        reprocessor.reprocess(device, Instant.parse("2026-07-01T00:00:00Z")) {}

        assertTrue("routeAllForceReplace was not called", captured.isCaptured)
        assertEquals(1, captured.captured.size)
        assertEquals(device.id, captured.captured[0].deviceId)
    }

    @Test
    fun `reprocess passes the device id to SleepStagePromoter promote`() = runBlocking {
        coEvery { driverRegistry.parseSession(any(), any()) } returns WasmParseResult.Empty

        reprocessor.reprocess(device, Instant.parse("2026-07-01T00:00:00Z")) {}

        coVerify { sleepStagePromoter.promote(any(), any(), any(), any(), device.id) }
    }

    // ---------------------------------------------------------------------------
    // Sleep-session and activity stamping (mergedSessions / acceptedActivities) are
    // structurally unreachable through the public reprocess() path today: allSleepSessions
    // and allActivities are hardcoded empty in DeviceReprocessor (sleep/activities arrive
    // embedded as MetricReading via parseSession, unlike DeviceSyncProcessor which gets
    // separate DriverSyncResult.sleepSessions/activities lists it can populate). Guard the
    // stamping source directly instead of faking a currently-impossible non-empty list
    // through the public API.
    // ---------------------------------------------------------------------------

    @Test
    fun `DeviceReprocessor source stamps deviceId onto merged sleep sessions and activities before writing`() {
        val sourceFile = java.io.File("src/main/java/com/athletedata/openAthleteMetrics/ble/sync/DeviceReprocessor.kt")
        assertTrue("expected to find DeviceReprocessor.kt at ${sourceFile.absolutePath}", sourceFile.exists())
        val source = sourceFile.readText()

        assertTrue(
            "sleepRepository.insertOrReplace must stamp deviceId = device.id after merge",
            source.contains("sleepRepository.insertOrReplace(it.copy(deviceId = device.id))"),
        )
        assertTrue(
            "activityRepository.replaceAllFromDevice must stamp deviceId = device.id",
            source.contains("activityRepository.replaceAllFromDevice(acceptedActivities.map { it.copy(deviceId = device.id) })"),
        )
    }
}
