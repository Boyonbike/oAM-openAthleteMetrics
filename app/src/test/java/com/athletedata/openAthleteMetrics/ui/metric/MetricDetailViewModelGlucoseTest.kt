package com.athletedata.openAthleteMetrics.ui.metric

import android.app.Application
import androidx.room.Room
import com.athletedata.openAthleteMetrics.data.db.AppDatabase
import com.athletedata.openAthleteMetrics.data.db.GlucoseReadingEntity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.data.model.MetricType
import com.athletedata.openAthleteMetrics.data.repository.RoomGlucoseReadingRepository
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Uses a real in-memory Room database for glucose_readings, the same approach as
 * MetricDetailViewModelHrvTest, so the per-reading unit conversion is actually exercised
 * end to end rather than hidden behind a mocked repository.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MetricDetailViewModelGlucoseTest {

    private lateinit var db: AppDatabase
    private lateinit var glucoseRepo: RoomGlucoseReadingRepository
    private lateinit var originalTimeZone: TimeZone

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        glucoseRepo = RoomGlucoseReadingRepository(db.glucoseReadingDao())
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
        TimeZone.setDefault(originalTimeZone)
    }

    private fun viewModel() = MetricDetailViewModel(
        metricType = MetricType.GLUCOSE,
        hrRepo = mockk(relaxed = true),
        hrvRepo = mockk(relaxed = true),
        spo2Repo = mockk(relaxed = true),
        respirationRepo = mockk(relaxed = true),
        skinTempRepo = mockk(relaxed = true),
        stepsRepo = mockk(relaxed = true),
        glucoseRepo = glucoseRepo,
        activeCalorieRepo = mockk(relaxed = true),
        totalCalorieRepo = mockk(relaxed = true),
        bloodPressureRepo = mockk(relaxed = true),
        dailySummaryRepo = mockk(relaxed = true),
        sleepRepo = mockk(relaxed = true),
    )

    private suspend fun insertReading(recordedAt: Instant, value: Double, unit: String) {
        glucoseRepo.insert(
            GlucoseReadingEntity(
                recordedAt = recordedAt,
                createdAt = recordedAt,
                source = DataSource.DEVICE,
                driverId = "hume-band-1",
                value = value,
                unit = unit,
            )
        )
    }

    private suspend fun historyValueFor(vm: MetricDetailViewModel, date: LocalDate): Double? {
        val state = vm.uiState.first { it !is MetricDetailUiState.Loading } as MetricDetailUiState.Success
        return state.historyRows.find { it.date == date }?.value
    }

    @Test
    fun `mg_dl readings are converted to mmol per L before averaging with mmol readings`() = runBlocking {
        val today = LocalDate.now(ZoneId.of("UTC"))
        // 5.0 mmol/L reading alongside a 90 mg_dl reading, which is 90 / 18.0182 = 4.9949... mmol/L.
        // A naive raw average (5.0 + 90) / 2 = 47.5 would be wildly wrong; the correct blend is
        // (5.0 + 4.9949...) / 2 ~= 4.9975 mmol/L.
        insertReading(today.atTime(8, 0).atZone(ZoneId.of("UTC")).toInstant(), 5.0, "mmol")
        insertReading(today.atTime(20, 0).atZone(ZoneId.of("UTC")).toInstant(), 90.0, "mg_dl")

        val vm = viewModel()

        val expected = (5.0 + 90.0 / 18.0182) / 2.0
        assertEquals(expected, historyValueFor(vm, today)!!, 0.0001)
    }

    @Test
    fun `an all mg_dl day is converted, not displayed on the mmol scale`() = runBlocking {
        val today = LocalDate.now(ZoneId.of("UTC"))
        insertReading(today.atTime(8, 0).atZone(ZoneId.of("UTC")).toInstant(), 90.0, "mg_dl")
        insertReading(today.atTime(20, 0).atZone(ZoneId.of("UTC")).toInstant(), 100.0, "mg_dl")

        val vm = viewModel()

        val expected = (90.0 / 18.0182 + 100.0 / 18.0182) / 2.0
        val actual = historyValueFor(vm, today)!!
        assertEquals(expected, actual, 0.0001)
        // Sanity check: a physiologically normal mg_dl day must land in mmol/L range (~4-10),
        // not be left on the raw mg_dl scale (~70-180).
        assert(actual in 4.0..10.0) { "expected $actual to be normalised into mmol/L range" }
    }
}
