package com.athletedata.openAthleteMetrics.ble.sync

import android.app.Application
import androidx.room.Room
import com.athletedata.openAthleteMetrics.ble.driver.DriverRegistry
import com.athletedata.openAthleteMetrics.data.db.AppDatabase
import com.athletedata.openAthleteMetrics.data.model.Activity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.data.model.Device
import com.athletedata.openAthleteMetrics.data.model.DriverSyncResult
import com.athletedata.openAthleteMetrics.data.model.SleepSession
import com.athletedata.openAthleteMetrics.data.repository.ActivityRepository
import com.athletedata.openAthleteMetrics.data.repository.DeviceRepository
import com.athletedata.openAthleteMetrics.data.repository.RawDeviceDataRepository
import com.athletedata.openAthleteMetrics.data.repository.RoomActivityRepository
import com.athletedata.openAthleteMetrics.data.repository.RoomSleepRepository
import com.athletedata.openAthleteMetrics.data.repository.SleepRepository
import com.athletedata.openAthleteMetrics.data.repository.SyncSessionRepository
import com.athletedata.openAthleteMetrics.domain.usecase.SyncActivityUseCase
import com.athletedata.openAthleteMetrics.worker.SleepStagePromoter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNotNull
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate

/**
 * Uses a real in-memory Room database for sleep/activity persistence (via the real
 * Room repositories and a real [SyncActivityUseCase]) so device_id stamping is observed
 * end-to-end through the actual toEntity()/toModel() mappers, while BLE-adjacent
 * collaborators (DriverRegistry, SyncSessionRepository, RawDeviceDataRepository,
 * DeviceRepository, SleepStagePromoter) are mocked. SyncValidator has no dependencies
 * so a real instance is used rather than mocking its accept/reject logic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DeviceSyncProcessorTest {

    private lateinit var db: AppDatabase
    private lateinit var sleepRepository: SleepRepository
    private lateinit var activityRepository: ActivityRepository
    private lateinit var syncActivityUseCase: SyncActivityUseCase
    private lateinit var syncSessionRepository: SyncSessionRepository
    private lateinit var rawDeviceDataRepository: RawDeviceDataRepository
    private lateinit var deviceRepository: DeviceRepository
    private lateinit var driverRegistry: DriverRegistry
    private lateinit var sleepStagePromoter: SleepStagePromoter
    private lateinit var processor: DeviceSyncProcessor

    private val resolvedDevice = Device(
        id = 99L,
        bleAddress = "AA:BB:CC:DD:EE:FF",
        driverId = "hume_band_v1",
        displayName = "Hume Band",
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sleepRepository = RoomSleepRepository(db.sleepSessionDao(), mockk(relaxed = true))
        activityRepository = RoomActivityRepository(db.activityDao(), mockk(relaxed = true))
        syncActivityUseCase = SyncActivityUseCase(activityRepository)
        syncSessionRepository = mockk(relaxed = true)
        rawDeviceDataRepository = mockk(relaxed = true)
        deviceRepository = mockk(relaxed = true)
        driverRegistry = mockk(relaxed = true)
        sleepStagePromoter = mockk(relaxed = true)

        coEvery { syncSessionRepository.insert(any()) } returns 1L
        coEvery { deviceRepository.getDeviceByAddress(resolvedDevice.bleAddress) } returns resolvedDevice
        every { driverRegistry.allDrivers() } returns emptyList()

        processor = DeviceSyncProcessor(
            appDatabase = db,
            sleepRepository = sleepRepository,
            activityRepository = activityRepository,
            syncActivityUseCase = syncActivityUseCase,
            syncSessionRepository = syncSessionRepository,
            rawDeviceDataRepository = rawDeviceDataRepository,
            deviceRepository = deviceRepository,
            driverRegistry = driverRegistry,
            validator = SyncValidator(),
            sleepStagePromoter = sleepStagePromoter,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun resultWith(sleepSessions: List<SleepSession>, activities: List<Activity>) = DriverSyncResult(
        deviceId = resolvedDevice.bleAddress,
        driverId = resolvedDevice.driverId,
        syncStartedAt = Instant.parse("2026-07-01T22:00:00Z"),
        syncEndedAt = Instant.parse("2026-07-01T23:00:00Z"),
        metricReadings = emptyList(),
        sleepSessions = sleepSessions,
        activities = activities,
        rawPayloads = emptyList(),
    )

    @Test
    fun `process stamps the resolved device id onto a new sleep session after merge`() = runBlocking {
        val session = SleepSession(
            date = LocalDate.parse("2026-07-01"),
            sleepStartMs = Instant.parse("2026-07-01T22:00:00Z"),
            sleepEndMs = Instant.parse("2026-07-01T22:30:00Z"),
            durationMinutes = 30,
            source = DataSource.DEVICE,
            driverId = resolvedDevice.driverId,
        )

        processor.process(resultWith(sleepSessions = listOf(session), activities = emptyList()))

        val stored = sleepRepository.getByDriverAndDate(resolvedDevice.driverId, LocalDate.parse("2026-07-01"))
        assertNotNull(stored)
        assertEquals(resolvedDevice.id, stored!!.deviceId)
    }

    @Test
    fun `process stamps device_id even when the merge base row is a pre-existing null-device session`() = runBlocking {
        // Pre-existing row with no device_id (e.g. written before device attribution existed).
        // mergeSleepSessions() bases its merged result on group.first(), which here is this
        // pre-existing row -- the stamping must happen AFTER merge, not before, or it would
        // be lost. See DeviceSyncProcessor.process()'s comment on this exact ordering.
        sleepRepository.insert(
            SleepSession(
                date = LocalDate.parse("2026-07-01"),
                sleepStartMs = Instant.parse("2026-07-01T22:00:00Z"),
                sleepEndMs = Instant.parse("2026-07-01T22:15:00Z"),
                durationMinutes = 15,
                source = DataSource.DEVICE,
                driverId = resolvedDevice.driverId,
            )
        )

        val incoming = SleepSession(
            date = LocalDate.parse("2026-07-01"),
            sleepStartMs = Instant.parse("2026-07-01T22:15:00Z"),
            sleepEndMs = Instant.parse("2026-07-01T22:30:00Z"),
            durationMinutes = 15,
            source = DataSource.DEVICE,
            driverId = resolvedDevice.driverId,
        )

        processor.process(resultWith(sleepSessions = listOf(incoming), activities = emptyList()))

        val stored = sleepRepository.getByDriverAndDate(resolvedDevice.driverId, LocalDate.parse("2026-07-01"))
        assertNotNull(stored)
        assertEquals(resolvedDevice.id, stored!!.deviceId)
        // Sanity: the merge itself still happened (envelope covers both spans).
        assertEquals(Instant.parse("2026-07-01T22:00:00Z"), stored.sleepStartMs)
        assertEquals(Instant.parse("2026-07-01T22:30:00Z"), stored.sleepEndMs)
    }

    @Test
    fun `process stamps the resolved device id onto activities routed through SyncActivityUseCase`() = runBlocking {
        val activity = Activity(
            startTime = Instant.parse("2026-07-01T08:00:00Z"),
            endTime = Instant.parse("2026-07-01T08:30:00Z"),
            durationMinutes = 30,
            deviceName = "Outdoor Run",
            source = DataSource.DEVICE,
            driverId = resolvedDevice.driverId,
        )

        processor.process(resultWith(sleepSessions = emptyList(), activities = listOf(activity)))

        val stored = activityRepository.getActivitiesForDateOnce(LocalDate.parse("2026-07-01"))
        assertEquals(1, stored.size)
        assertEquals(resolvedDevice.id, stored[0].deviceId)
    }

    @Test
    fun `process passes the resolved device id to SleepStagePromoter promote`() = runBlocking {
        processor.process(resultWith(sleepSessions = emptyList(), activities = emptyList()))

        coVerify { sleepStagePromoter.promote(any(), any(), any(), any(), resolvedDevice.id) }
    }
}
