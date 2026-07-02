package com.athletedata.openAthleteMetrics.ble.sync

import com.athletedata.openAthleteMetrics.ble.driver.CaloriesMode
import com.athletedata.openAthleteMetrics.ble.driver.StepsMode
import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.data.model.MetricReading
import com.athletedata.openAthleteMetrics.data.model.MetricType
import com.athletedata.openAthleteMetrics.data.repository.ActiveCalorieReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.BloodPressureReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.GlucoseReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.HrReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.HrvReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.MetricReadingStagingRepository
import com.athletedata.openAthleteMetrics.data.repository.RespirationReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.SkinTempReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.SpO2ReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.StepsReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.TotalCalorieReadingRepository
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import java.time.Instant

class MetricRouterTest {

    private lateinit var hrReadingRepository: HrReadingRepository
    private lateinit var hrvReadingRepository: HrvReadingRepository
    private lateinit var spo2ReadingRepository: SpO2ReadingRepository
    private lateinit var respirationReadingRepository: RespirationReadingRepository
    private lateinit var skinTempReadingRepository: SkinTempReadingRepository
    private lateinit var stepsReadingRepository: StepsReadingRepository
    private lateinit var activeCalorieReadingRepository: ActiveCalorieReadingRepository
    private lateinit var totalCalorieReadingRepository: TotalCalorieReadingRepository
    private lateinit var bloodPressureReadingRepository: BloodPressureReadingRepository
    private lateinit var glucoseReadingRepository: GlucoseReadingRepository
    private lateinit var stagingRepository: MetricReadingStagingRepository
    private lateinit var router: MetricRouter

    @Before
    fun setUp() {
        hrReadingRepository = mockk(relaxed = true)
        hrvReadingRepository = mockk(relaxed = true)
        spo2ReadingRepository = mockk(relaxed = true)
        respirationReadingRepository = mockk(relaxed = true)
        skinTempReadingRepository = mockk(relaxed = true)
        stepsReadingRepository = mockk(relaxed = true)
        activeCalorieReadingRepository = mockk(relaxed = true)
        totalCalorieReadingRepository = mockk(relaxed = true)
        bloodPressureReadingRepository = mockk(relaxed = true)
        glucoseReadingRepository = mockk(relaxed = true)
        stagingRepository = mockk(relaxed = true)
        router = MetricRouter(
            hrReadingRepository, hrvReadingRepository, spo2ReadingRepository,
            respirationReadingRepository, skinTempReadingRepository, stepsReadingRepository,
            activeCalorieReadingRepository, totalCalorieReadingRepository,
            bloodPressureReadingRepository, glucoseReadingRepository, stagingRepository,
        )
    }

    private fun readingOf(
        metricType: MetricType,
        value: Double,
        unit: String,
        recordedAt: Instant = Instant.parse("2026-06-22T12:00:00Z"),
    ) = MetricReading(
        metricType = metricType,
        value = value,
        unit = unit,
        recordedAt = recordedAt,
        createdAt = Instant.now(),
        source = DataSource.DEVICE,
        driverId = "hume-band-1",
    )

    // ---------------------------------------------------------------------------
    // Reference incident: 2026-06-22 stale 0x52 frame
    // ---------------------------------------------------------------------------

    @Test
    fun `route rejects the 2026-06-22 stale frame steps and calories readings`() = runBlocking {
        router.route(readingOf(MetricType.STEPS, 64767.0, "count"))
        router.route(readingOf(MetricType.ACTIVE_CALORIES, 501.83, "kcal"))

        coVerify(exactly = 0) { stepsReadingRepository.insert(any()) }
        coVerify(exactly = 0) { activeCalorieReadingRepository.insert(any()) }
    }

    @Test
    fun `route passes through a normal steps delta within the 5-minute range`() = runBlocking {
        router.route(readingOf(MetricType.STEPS, 850.0, "count"))

        coVerify(exactly = 1) { stepsReadingRepository.insert(any()) }
    }

    // ---------------------------------------------------------------------------
    // Per-type bounds
    // ---------------------------------------------------------------------------

    @Test
    fun `route rejects active calories delta above the 150 kcal per interval ceiling`() = runBlocking {
        router.route(readingOf(MetricType.ACTIVE_CALORIES, 150.01, "kcal"))

        coVerify(exactly = 0) { activeCalorieReadingRepository.insert(any()) }
    }

    @Test
    fun `route accepts active calories delta at the 150 kcal per interval ceiling`() = runBlocking {
        router.route(readingOf(MetricType.ACTIVE_CALORIES, 150.0, "kcal"))

        coVerify(exactly = 1) { activeCalorieReadingRepository.insert(any()) }
    }

    @Test
    fun `route rejects a negative steps delta`() = runBlocking {
        router.route(readingOf(MetricType.STEPS, -5.0, "count"))

        coVerify(exactly = 0) { stepsReadingRepository.insert(any()) }
    }

    // ---------------------------------------------------------------------------
    // ABSOLUTE mode is exempt from the STEPS/ACTIVE_CALORIES bound
    // ---------------------------------------------------------------------------

    @Test
    fun `route accepts a large steps value in ABSOLUTE mode despite exceeding the DELTA ceiling`() = runBlocking {
        router.route(readingOf(MetricType.STEPS, 12000.0, "count"), stepsMode = StepsMode.ABSOLUTE)

        coVerify(exactly = 1) { stepsReadingRepository.insert(any()) }
    }

    // ---------------------------------------------------------------------------
    // DISTANCE is intentionally excluded from magnitude filtering (no dedicated
    // consumer in the app today — see MetricRouter's MAX_DELTA_PER_INTERVAL comment)
    // ---------------------------------------------------------------------------

    @Test
    fun `route passes distance readings of any magnitude through to staging unaffected`() = runBlocking {
        router.route(readingOf(MetricType.DISTANCE, 307440.0, "m"))

        coVerify(exactly = 1) { stagingRepository.insert(any()) }
    }
}
