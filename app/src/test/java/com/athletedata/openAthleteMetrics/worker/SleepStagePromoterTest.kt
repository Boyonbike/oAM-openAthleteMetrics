package com.athletedata.openAthleteMetrics.worker

import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.data.model.MetricReading
import com.athletedata.openAthleteMetrics.data.model.MetricType
import com.athletedata.openAthleteMetrics.data.model.SleepSession
import com.athletedata.openAthleteMetrics.data.model.SleepStage
import com.athletedata.openAthleteMetrics.data.repository.MetricReadingStagingRepository
import com.athletedata.openAthleteMetrics.data.repository.SleepRepository
import com.athletedata.openAthleteMetrics.data.repository.SleepStageRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

class SleepStagePromoterTest {

    private lateinit var stagingRepository: MetricReadingStagingRepository
    private lateinit var sleepRepository: SleepRepository
    private lateinit var sleepStageRepository: SleepStageRepository
    private lateinit var promoter: SleepStagePromoter
    private lateinit var originalTimeZone: TimeZone

    @Before
    fun setUp() {
        originalTimeZone = TimeZone.getDefault()
        stagingRepository = mockk(relaxed = true)
        sleepRepository = mockk(relaxed = true)
        sleepStageRepository = mockk(relaxed = true)
        promoter = SleepStagePromoter(stagingRepository, sleepRepository, sleepStageRepository)
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalTimeZone)
    }

    private fun stagingRowOf(
        id: Long,
        stage: SleepStage,
        startMs: Long,
        endMs: Long,
        driverId: String = "hume-band-1",
    ) = MetricReading(
        id = id,
        metricType = MetricType.SLEEP_STAGE,
        value = 0.0,
        unit = "stage",
        recordedAt = Instant.ofEpochMilli(startMs),
        createdAt = Instant.now(),
        source = DataSource.DEVICE,
        driverId = driverId,
        metaJson = """{"pending_sleep_stage":true,"stage":"${stage.name}","start_ms":$startMs,"end_ms":$endMs}""",
    )

    @Test
    fun `promote inserts a sleep session and stages for pending SLEEP_STAGE rows`() = runBlocking {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val date = LocalDate.parse("2026-06-15")
        val startMs = Instant.parse("2026-06-15T22:00:00Z").toEpochMilli()
        val endMs = Instant.parse("2026-06-15T22:30:00Z").toEpochMilli()
        val row = stagingRowOf(id = 1L, stage = SleepStage.DEEP, startMs = startMs, endMs = endMs)

        coEvery {
            stagingRepository.getPendingSleepStages(DataSource.DEVICE, "hume-band-1", any(), any())
        } returns listOf(row)
        coEvery { sleepRepository.getByDriverAndDate("hume-band-1", date) } returnsMany listOf(
            null,
            SleepSession(
                id = 42L,
                date = date,
                sleepStartMs = Instant.ofEpochMilli(startMs),
                sleepEndMs = Instant.ofEpochMilli(endMs),
                durationMinutes = 30,
                source = DataSource.DEVICE,
                driverId = "hume-band-1",
            ),
        )
        coEvery { sleepStageRepository.insertAllOrIgnore(any()) } returns listOf(1L)

        val result = promoter.promote("hume-band-1", startMs, endMs)

        coVerify(exactly = 1) {
            sleepRepository.insert(match {
                it.date == date && it.durationMinutes == 30 && it.driverId == "hume-band-1"
            })
        }
        coVerify(exactly = 1) { sleepStageRepository.insertAllOrIgnore(any()) }
        assertEquals(listOf(date), result.datesProcessed)
        assertEquals(1, result.sessionsCreated)
        assertEquals(1, result.stagesInserted)
    }

    @Test
    fun `promote buckets a sleep stage crossing UTC midnight under the local calendar date`() = runBlocking {
        // Fixed-offset zone (no DST) behind UTC: 23:30 local on 06-15 is 06:30 UTC on 06-16.
        val localZone = ZoneId.of("Etc/GMT+7")
        TimeZone.setDefault(TimeZone.getTimeZone(localZone))
        val localDate = LocalDate.parse("2026-06-15")
        val nextUtcDate = LocalDate.parse("2026-06-16")
        val startMs = LocalDateTime.parse("2026-06-15T23:30:00").atZone(localZone).toInstant().toEpochMilli()
        val endMs = LocalDateTime.parse("2026-06-16T00:15:00").atZone(localZone).toInstant().toEpochMilli()
        val row = stagingRowOf(id = 2L, stage = SleepStage.LIGHT, startMs = startMs, endMs = endMs)

        coEvery {
            stagingRepository.getPendingSleepStages(DataSource.DEVICE, "hume-band-1", any(), any())
        } returns listOf(row)
        coEvery { sleepRepository.getByDriverAndDate("hume-band-1", localDate) } returnsMany listOf(
            null,
            SleepSession(
                id = 7L,
                date = localDate,
                sleepStartMs = Instant.ofEpochMilli(startMs),
                sleepEndMs = Instant.ofEpochMilli(endMs),
                durationMinutes = 45,
                source = DataSource.DEVICE,
                driverId = "hume-band-1",
            ),
        )
        coEvery { sleepStageRepository.insertAllOrIgnore(any()) } returns listOf(1L)

        val result = promoter.promote("hume-band-1", startMs, endMs)

        // Pre-fix (ZoneOffset.UTC) this would bucket under 2026-06-16; post-fix it must
        // match ZoneId.systemDefault(), i.e. the local calendar date, 2026-06-15.
        coVerify(exactly = 1) {
            sleepRepository.insert(match { it.date == localDate })
        }
        coVerify(exactly = 0) { sleepRepository.getByDriverAndDate("hume-band-1", nextUtcDate) }
        assertEquals(listOf(localDate), result.datesProcessed)
    }
}
