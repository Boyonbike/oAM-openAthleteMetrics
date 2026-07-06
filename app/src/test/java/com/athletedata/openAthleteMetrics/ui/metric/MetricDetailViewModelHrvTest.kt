package com.athletedata.openAthleteMetrics.ui.metric

import android.app.Application
import androidx.room.Room
import androidx.work.WorkManager
import com.athletedata.openAthleteMetrics.data.db.AppDatabase
import com.athletedata.openAthleteMetrics.data.db.HrvReadingEntity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.data.model.MetricType
import com.athletedata.openAthleteMetrics.data.model.SleepSession
import com.athletedata.openAthleteMetrics.data.repository.RoomHrvReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.RoomSleepRepository
import com.athletedata.openAthleteMetrics.worker.OvernightHrvCalculator
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/**
 * Uses a real in-memory Room database for hrv_readings/sleep_sessions so the
 * sleep-session-window bucketing is actually exercised end to end, the same
 * way SleepStagePromoterTest does for its own midnight-crossing bug -- a
 * mocked repository would hide exactly this class of boundary bug.
 *
 * Runs on Dispatchers.Unconfined (real, not a virtual TestDispatcher) plus
 * plain runBlocking, like SleepStagePromoterTest: Room's suspend/Flow DAO
 * calls hop onto its own real query executor threads, which a virtual
 * StandardTestDispatcher's advanceUntilIdle() cannot deterministically wait
 * on -- real dispatchers avoid that race entirely.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MetricDetailViewModelHrvTest {

    private lateinit var db: AppDatabase
    private lateinit var hrvRepo: RoomHrvReadingRepository
    private lateinit var sleepRepo: RoomSleepRepository
    private lateinit var originalTimeZone: TimeZone

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        hrvRepo = RoomHrvReadingRepository(db.hrvReadingDao())
        sleepRepo = RoomSleepRepository(db.sleepSessionDao(), mockk(relaxed = true))
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
        TimeZone.setDefault(originalTimeZone)
    }

    private fun viewModel() = MetricDetailViewModel(
        metricType = MetricType.HRV,
        hrRepo = mockk(relaxed = true),
        hrvRepo = hrvRepo,
        spo2Repo = mockk(relaxed = true),
        respirationRepo = mockk(relaxed = true),
        skinTempRepo = mockk(relaxed = true),
        stepsRepo = mockk(relaxed = true),
        glucoseRepo = mockk(relaxed = true),
        activeCalorieRepo = mockk(relaxed = true),
        totalCalorieRepo = mockk(relaxed = true),
        bloodPressureRepo = mockk(relaxed = true),
        dailySummaryRepo = mockk(relaxed = true),
        sleepRepo = sleepRepo,
    )

    private suspend fun insertReading(recordedAt: Instant, rmssdMs: Double) {
        hrvRepo.insert(
            HrvReadingEntity(
                recordedAt = recordedAt,
                createdAt = recordedAt,
                source = DataSource.DEVICE,
                driverId = "hume-band-1",
                rmssdMs = rmssdMs,
                computedByVersion = 1,
            )
        )
    }

    private suspend fun insertSession(date: LocalDate, sleepStartMs: Instant, sleepEndMs: Instant) {
        sleepRepo.insert(
            SleepSession(
                date = date,
                sleepStartMs = sleepStartMs,
                sleepEndMs = sleepEndMs,
                durationMinutes = ((sleepEndMs.toEpochMilli() - sleepStartMs.toEpochMilli()) / 60_000).toInt(),
                source = DataSource.DEVICE,
                driverId = "hume-band-1",
            )
        )
    }

    private suspend fun historyValueFor(vm: MetricDetailViewModel, date: LocalDate): Double? {
        val state = vm.uiState.first { it !is MetricDetailUiState.Loading } as MetricDetailUiState.Success
        return state.historyRows.find { it.date == date }?.value
    }

    @Test
    fun `pre-midnight reading is bucketed under the session's wake date, not dropped`() = runBlocking {
        val today = LocalDate.now(ZoneId.of("UTC"))
        val yesterday = today.minusDays(1)
        val sleepStart = yesterday.atTime(23, 0).atZone(ZoneId.of("UTC")).toInstant()
        val sleepEnd = today.atTime(6, 0).atZone(ZoneId.of("UTC")).toInstant()
        insertSession(today, sleepStart, sleepEnd)
        insertReading(yesterday.atTime(23, 30).atZone(ZoneId.of("UTC")).toInstant(), 45.0)

        val vm = viewModel()

        assertEquals(45.0, historyValueFor(vm, today))
        assertEquals(null, historyValueFor(vm, yesterday))
    }

    @Test
    fun `readings spanning midnight in the same session match OvernightHrvCalculator`() = runBlocking {
        val today = LocalDate.now(ZoneId.of("UTC"))
        val yesterday = today.minusDays(1)
        val sleepStart = yesterday.atTime(23, 0).atZone(ZoneId.of("UTC")).toInstant()
        val sleepEnd = today.atTime(6, 0).atZone(ZoneId.of("UTC")).toInstant()
        insertSession(today, sleepStart, sleepEnd)
        insertReading(yesterday.atTime(23, 30).atZone(ZoneId.of("UTC")).toInstant(), 40.0)
        insertReading(today.atTime(2, 0).atZone(ZoneId.of("UTC")).toInstant(), 50.0)
        insertReading(today.atTime(5, 0).atZone(ZoneId.of("UTC")).toInstant(), 60.0)

        val vm = viewModel()

        val expected = OvernightHrvCalculator(db.hrvReadingDao(), db.sleepSessionDao()).calculate(today)
        assertNotNull(expected)
        assertEquals(expected, historyValueFor(vm, today))
        assertEquals(50.0, historyValueFor(vm, today))
    }

    @Test
    fun `daytime reading with no covering sleep session falls back to calendar date`() = runBlocking {
        val today = LocalDate.now(ZoneId.of("UTC"))
        insertReading(today.atTime(14, 0).atZone(ZoneId.of("UTC")).toInstant(), 42.0)

        val vm = viewModel()

        assertEquals(42.0, historyValueFor(vm, today))
    }

    @Test
    fun `readings outside 10-250ms are excluded from the bucket average`() = runBlocking {
        val today = LocalDate.now(ZoneId.of("UTC"))
        val yesterday = today.minusDays(1)
        val sleepStart = yesterday.atTime(23, 0).atZone(ZoneId.of("UTC")).toInstant()
        val sleepEnd = today.atTime(6, 0).atZone(ZoneId.of("UTC")).toInstant()
        insertSession(today, sleepStart, sleepEnd)
        insertReading(today.atTime(1, 0).atZone(ZoneId.of("UTC")).toInstant(), 5.0) // below floor
        insertReading(today.atTime(2, 0).atZone(ZoneId.of("UTC")).toInstant(), 300.0) // above ceiling
        insertReading(today.atTime(3, 0).atZone(ZoneId.of("UTC")).toInstant(), 50.0)
        insertReading(today.atTime(4, 0).atZone(ZoneId.of("UTC")).toInstant(), 60.0)

        val vm = viewModel()

        assertEquals(55.0, historyValueFor(vm, today))
    }
}
