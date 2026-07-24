package com.athletedata.openAthleteMetrics.ble.sync

import com.athletedata.openAthleteMetrics.ble.driver.CaloriesMode
import com.athletedata.openAthleteMetrics.ble.driver.StepsMode
import com.athletedata.openAthleteMetrics.data.db.HrReadingEntity
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
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    // consumer in the app today — see MetricRouter's MAX_PLAUSIBLE_RATE_PER_MINUTE comment)
    // ---------------------------------------------------------------------------

    @Test
    fun `route passes distance readings of any magnitude through to staging unaffected`() = runBlocking {
        router.route(readingOf(MetricType.DISTANCE, 307440.0, "m"))

        coVerify(exactly = 1) { stagingRepository.insert(any()) }
    }

    // ---------------------------------------------------------------------------
    // CHANGED — regression: Hume Band's ceiling is unchanged when its manifest
    // explicitly declares syncIntervalMs = 300_000 (5 minutes), the value now written
    // to HumeBandDriver.json and previously implicit as MetricRouter's only behaviour.
    // ---------------------------------------------------------------------------

    @Test
    fun `route rejects the 2026-06-22 stale frame readings with Hume Band's declared 5-minute interval`() = runBlocking {
        router.route(readingOf(MetricType.STEPS, 64767.0, "count"), syncIntervalMs = 300_000L)
        router.route(readingOf(MetricType.ACTIVE_CALORIES, 501.83, "kcal"), syncIntervalMs = 300_000L)

        coVerify(exactly = 0) { stepsReadingRepository.insert(any()) }
        coVerify(exactly = 0) { activeCalorieReadingRepository.insert(any()) }
    }

    @Test
    fun `route passes a normal steps delta with Hume Band's declared 5-minute interval`() = runBlocking {
        router.route(readingOf(MetricType.STEPS, 850.0, "count"), syncIntervalMs = 300_000L)

        coVerify(exactly = 1) { stepsReadingRepository.insert(any()) }
    }

    @Test
    fun `route accepts active calories at the 150 kcal ceiling with Hume Band's declared 5-minute interval`() = runBlocking {
        router.route(readingOf(MetricType.ACTIVE_CALORIES, 150.0, "kcal"), syncIntervalMs = 300_000L)

        coVerify(exactly = 1) { activeCalorieReadingRepository.insert(any()) }
    }

    @Test
    fun `route rejects active calories above the 150 kcal ceiling with Hume Band's declared 5-minute interval`() = runBlocking {
        router.route(readingOf(MetricType.ACTIVE_CALORIES, 150.01, "kcal"), syncIntervalMs = 300_000L)

        coVerify(exactly = 0) { activeCalorieReadingRepository.insert(any()) }
    }

    // ---------------------------------------------------------------------------
    // CHANGED — a driver declaring a shorter syncIntervalMs gets a correspondingly
    // tighter ceiling: 400 steps/min and 30 kcal/min scaled by a 60s interval gives
    // 400.0 and 30.0, versus 2000.0 and 150.0 at Hume Band's 5-minute interval.
    // ---------------------------------------------------------------------------

    @Test
    fun `route rejects a steps delta that would pass Hume Band's ceiling but exceeds a 60s-interval driver's ceiling`() = runBlocking {
        router.route(readingOf(MetricType.STEPS, 500.0, "count"), syncIntervalMs = 60_000L)

        coVerify(exactly = 0) { stepsReadingRepository.insert(any()) }
    }

    @Test
    fun `route accepts a steps delta at the scaled 400 ceiling for a 60s-interval driver`() = runBlocking {
        router.route(readingOf(MetricType.STEPS, 400.0, "count"), syncIntervalMs = 60_000L)

        coVerify(exactly = 1) { stepsReadingRepository.insert(any()) }
    }

    @Test
    fun `route rejects a steps delta just above the scaled 400 ceiling for a 60s-interval driver`() = runBlocking {
        router.route(readingOf(MetricType.STEPS, 400.01, "count"), syncIntervalMs = 60_000L)

        coVerify(exactly = 0) { stepsReadingRepository.insert(any()) }
    }

    @Test
    fun `route accepts an active calories delta at the scaled 30 ceiling for a 60s-interval driver`() = runBlocking {
        router.route(readingOf(MetricType.ACTIVE_CALORIES, 30.0, "kcal"), syncIntervalMs = 60_000L)

        coVerify(exactly = 1) { activeCalorieReadingRepository.insert(any()) }
    }

    @Test
    fun `route rejects an active calories delta just above the scaled 30 ceiling for a 60s-interval driver`() = runBlocking {
        router.route(readingOf(MetricType.ACTIVE_CALORIES, 30.01, "kcal"), syncIntervalMs = 60_000L)

        coVerify(exactly = 0) { activeCalorieReadingRepository.insert(any()) }
    }

    // ---------------------------------------------------------------------------
    // CHANGED — structural regression: MetricRouter's plausibility filter must stay
    // keyed only on MetricType + syncIntervalMs, never on driver identity. Mirrors
    // EndOfStreamStrategyTest's "source contains no Hume Band opcode or vendor
    // literal" pattern.
    // ---------------------------------------------------------------------------

    @Test
    fun `MetricRouter source contains no driver-identity branching or device-specific literal`() {
        val sourceFile = java.io.File("src/main/java/com/athletedata/openAthleteMetrics/ble/sync/MetricRouter.kt")
        assertTrue("expected to find MetricRouter.kt at ${sourceFile.absolutePath}", sourceFile.exists())
        val source = sourceFile.readText()

        val branchingPatterns = listOf(
            Regex("""driverId\s*=="""),
            Regex("""driver_id\s*=="""),
            Regex("""manifest\.id\s*=="""),
        )
        for (pattern in branchingPatterns) {
            assertFalse(
                "MetricRouter.kt must not branch on driver identity (matched: ${pattern.pattern}) — " +
                    "the plausibility ceiling must be derived from MetricType + syncIntervalMs only",
                pattern.containsMatchIn(source),
            )
        }

        assertFalse(
            "MetricRouter.kt must not name a specific device — the plausibility policy is generic app policy",
            source.contains("Hume", ignoreCase = true),
        )
    }

    // ---------------------------------------------------------------------------
    // A driver with no declared syncIntervalMs (default null) still uses the
    // 5-minute fallback, matching the pre-existing implicit-default tests above.
    // ---------------------------------------------------------------------------

    @Test
    fun `route accepts a steps delta at the 2000 default ceiling when syncIntervalMs is not declared`() = runBlocking {
        router.route(readingOf(MetricType.STEPS, 2000.0, "count"))

        coVerify(exactly = 1) { stepsReadingRepository.insert(any()) }
    }

    @Test
    fun `route rejects a steps delta just above the 2000 default ceiling when syncIntervalMs is not declared`() = runBlocking {
        router.route(readingOf(MetricType.STEPS, 2000.01, "count"))

        coVerify(exactly = 0) { stepsReadingRepository.insert(any()) }
    }

    // ---------------------------------------------------------------------------
    // deviceId (physical devices.id, not the driver) rides through on MetricReading
    // and must land on the persisted entity via the toXEntity()/toStagingEntity()
    // mappers -- route()/routeAllForceReplace() take no separate device parameter.
    // ---------------------------------------------------------------------------

    @Test
    fun `route carries deviceId from MetricReading into the typed HR entity`() = runBlocking {
        val captured = slot<HrReadingEntity>()
        coEvery { hrReadingRepository.insert(capture(captured)) } just Runs

        router.route(readingOf(MetricType.HR, 60.0, "bpm").copy(deviceId = 99L))

        assertEquals(99L, captured.captured.deviceId)
    }

    @Test
    fun `route preserves a null deviceId into the typed HR entity`() = runBlocking {
        val captured = slot<HrReadingEntity>()
        coEvery { hrReadingRepository.insert(capture(captured)) } just Runs

        router.route(readingOf(MetricType.HR, 60.0, "bpm"))

        assertNull(captured.captured.deviceId)
    }

    @Test
    fun `route carries deviceId into staging for a fall-through metric type`() = runBlocking {
        val captured = slot<MetricReading>()
        coEvery { stagingRepository.insert(capture(captured)) } just Runs

        router.route(readingOf(MetricType.DISTANCE, 307440.0, "m").copy(deviceId = 42L))

        assertEquals(42L, captured.captured.deviceId)
    }
}
