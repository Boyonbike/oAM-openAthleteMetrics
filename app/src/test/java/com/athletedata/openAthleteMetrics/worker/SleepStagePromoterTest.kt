package com.athletedata.openAthleteMetrics.worker

import android.app.Application
import androidx.room.Room
import androidx.work.WorkManager
import com.athletedata.openAthleteMetrics.data.db.AppDatabase
import com.athletedata.openAthleteMetrics.data.db.MetricReadingStagingEntity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.data.model.MetricType
import com.athletedata.openAthleteMetrics.data.model.SleepSession
import com.athletedata.openAthleteMetrics.data.model.SleepStage
import com.athletedata.openAthleteMetrics.data.repository.MetricReadingStagingRepository
import com.athletedata.openAthleteMetrics.data.repository.RoomMetricReadingStagingRepository
import com.athletedata.openAthleteMetrics.data.repository.SleepRepository
import com.athletedata.openAthleteMetrics.data.repository.SleepStageRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/**
 * Uses a real in-memory Room database for the staging DAO so the
 * getPendingSleepStages SQL predicate (created_at, not recorded_at) is
 * actually exercised — mocking it with any()/any() previously hid the bug
 * where recorded_at (historical sleep time) never overlaps the live sync
 * window. sleepRepository/sleepStageRepository stay mocked; they're outside
 * the bug's blast radius.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SleepStagePromoterTest {

    private lateinit var db: AppDatabase
    private lateinit var stagingRepository: MetricReadingStagingRepository
    private lateinit var sleepRepository: SleepRepository
    private lateinit var sleepStageRepository: SleepStageRepository
    private lateinit var promoter: SleepStagePromoter
    private lateinit var originalTimeZone: TimeZone

    @Before
    fun setUp() {
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        stagingRepository = RoomMetricReadingStagingRepository(db.metricReadingStagingDao(), mockk<WorkManager>(relaxed = true))
        sleepRepository = mockk(relaxed = true)
        sleepStageRepository = mockk(relaxed = true)
        promoter = SleepStagePromoter(stagingRepository, sleepRepository, sleepStageRepository)
    }

    @After
    fun tearDown() {
        db.close()
        TimeZone.setDefault(originalTimeZone)
    }

    private fun stagingRowOf(
        stage: SleepStage,
        recordedAt: Instant,
        createdAt: Instant,
        startMs: Long,
        endMs: Long,
        driverId: String = "hume-band-1",
    ) = MetricReadingStagingEntity(
        metricType = MetricType.SLEEP_STAGE,
        value = 0.0,
        unit = "stage",
        recordedAt = recordedAt,
        createdAt = createdAt,
        source = DataSource.DEVICE,
        driverId = driverId,
        metaJson = """{"pending_sleep_stage":true,"stage":"${stage.name}","start_ms":$startMs,"end_ms":$endMs}""",
    )

    @Test
    fun `promote picks up a staging row whose created_at falls in the sync window even though recorded_at is historical`() = runBlocking {
        // recorded_at: 22:00 the prior night (historical sleep time, decoded from the device).
        // created_at: 07:00 this morning, inside the live sync window (when this sync inserted the row).
        val sleepStart = Instant.parse("2026-07-01T22:00:00Z")
        val sleepEnd = Instant.parse("2026-07-01T22:30:00Z")
        val insertedAt = Instant.parse("2026-07-02T07:00:00Z")
        val syncWindowStartMs = Instant.parse("2026-07-02T06:55:00Z").toEpochMilli()
        val syncWindowEndMs = Instant.parse("2026-07-02T07:05:00Z").toEpochMilli()

        db.metricReadingStagingDao().insert(
            stagingRowOf(
                stage = SleepStage.DEEP,
                recordedAt = sleepStart,
                createdAt = insertedAt,
                startMs = sleepStart.toEpochMilli(),
                endMs = sleepEnd.toEpochMilli(),
            )
        )

        val pending = stagingRepository.getPendingSleepStages(
            source = DataSource.DEVICE,
            driverId = "hume-band-1",
            syncWindowStartMs = syncWindowStartMs,
            syncWindowEndMs = syncWindowEndMs,
        )
        assertEquals(1, pending.size)

        val expectedDate = LocalDate.parse("2026-07-01")
        coEvery { sleepRepository.getByDriverAndDate("hume-band-1", expectedDate) } returnsMany listOf(
            null,
            SleepSession(
                id = 1L,
                date = expectedDate,
                sleepStartMs = sleepStart,
                sleepEndMs = sleepEnd,
                durationMinutes = 30,
                source = DataSource.DEVICE,
                driverId = "hume-band-1",
            ),
        )
        coEvery { sleepStageRepository.insertAllOrIgnore(any()) } returns listOf(1L)

        val result = promoter.promote("hume-band-1", syncWindowStartMs, syncWindowEndMs)

        coVerify(exactly = 1) {
            sleepRepository.insert(match { it.date == expectedDate && it.driverId == "hume-band-1" })
        }
        assertEquals(listOf(expectedDate), result.datesProcessed)
        assertEquals(1, result.sessionsCreated)
        assertEquals(1, result.stagesInserted)
    }

    @Test
    fun `promote does not pick up a staging row created outside the sync window`() = runBlocking {
        // Row inserted by a previous, already-processed sync session — created_at is well
        // before this sync's window, even though recorded_at (historical sleep time) falls
        // on the same night as the passing-direction test above.
        val sleepStart = Instant.parse("2026-07-01T22:00:00Z")
        val sleepEnd = Instant.parse("2026-07-01T22:30:00Z")
        val previousSyncInsertedAt = Instant.parse("2026-07-01T23:00:00Z")
        val syncWindowStartMs = Instant.parse("2026-07-02T06:55:00Z").toEpochMilli()
        val syncWindowEndMs = Instant.parse("2026-07-02T07:05:00Z").toEpochMilli()

        db.metricReadingStagingDao().insert(
            stagingRowOf(
                stage = SleepStage.DEEP,
                recordedAt = sleepStart,
                createdAt = previousSyncInsertedAt,
                startMs = sleepStart.toEpochMilli(),
                endMs = sleepEnd.toEpochMilli(),
            )
        )

        val pending = stagingRepository.getPendingSleepStages(
            source = DataSource.DEVICE,
            driverId = "hume-band-1",
            syncWindowStartMs = syncWindowStartMs,
            syncWindowEndMs = syncWindowEndMs,
        )
        assertTrue(pending.isEmpty())

        val result = promoter.promote("hume-band-1", syncWindowStartMs, syncWindowEndMs)

        coVerify(exactly = 0) { sleepRepository.insert(any()) }
        assertEquals(0, result.sessionsCreated)
        assertEquals(emptyList<LocalDate>(), result.datesProcessed)
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
        // Synced an hour after the stage ended — an arbitrary sync window, independent of
        // the historical start/end times embedded in meta_json.
        val insertedAt = Instant.ofEpochMilli(endMs).plusSeconds(3600)
        val syncWindowStartMs = insertedAt.minusSeconds(300).toEpochMilli()
        val syncWindowEndMs = insertedAt.plusSeconds(300).toEpochMilli()

        db.metricReadingStagingDao().insert(
            stagingRowOf(
                stage = SleepStage.LIGHT,
                recordedAt = Instant.ofEpochMilli(startMs),
                createdAt = insertedAt,
                startMs = startMs,
                endMs = endMs,
            )
        )

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

        val result = promoter.promote("hume-band-1", syncWindowStartMs, syncWindowEndMs)

        // Pre-fix (ZoneOffset.UTC) this would bucket under 2026-06-16; post-fix it must
        // match ZoneId.systemDefault(), i.e. the local calendar date, 2026-06-15.
        coVerify(exactly = 1) {
            sleepRepository.insert(match { it.date == localDate })
        }
        coVerify(exactly = 0) { sleepRepository.getByDriverAndDate("hume-band-1", nextUtcDate) }
        assertEquals(listOf(localDate), result.datesProcessed)
    }
}
