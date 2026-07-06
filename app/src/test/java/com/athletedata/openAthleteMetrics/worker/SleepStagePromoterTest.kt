package com.athletedata.openAthleteMetrics.worker

import android.app.Application
import androidx.room.Room
import androidx.work.WorkManager
import com.athletedata.openAthleteMetrics.data.db.AppDatabase
import com.athletedata.openAthleteMetrics.data.db.MetricReadingStagingEntity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.data.model.MetricType
import com.athletedata.openAthleteMetrics.data.model.SleepStage
import com.athletedata.openAthleteMetrics.data.repository.MetricReadingStagingRepository
import com.athletedata.openAthleteMetrics.data.repository.RoomMetricReadingStagingRepository
import com.athletedata.openAthleteMetrics.data.repository.RoomSleepRepository
import com.athletedata.openAthleteMetrics.data.repository.RoomSleepStageRepository
import com.athletedata.openAthleteMetrics.data.repository.SleepRepository
import com.athletedata.openAthleteMetrics.data.repository.SleepStageRepository
import io.mockk.mockk
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertNull
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
 * Uses a real in-memory Room database for every repository the promoter touches — staging,
 * sleep sessions, and sleep stages — so both the getPendingSleepStages SQL predicate
 * (created_at, not recorded_at) and the session-bucketing/merge behavior are exercised
 * end-to-end, the same way DailyDetailViewModel's getSessionForDate/getStagesForSessionOnce
 * would see the result. Only WorkManager (an Android system service, not app logic) is mocked.
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
        sleepRepository = RoomSleepRepository(db.sleepSessionDao(), mockk(relaxed = true))
        sleepStageRepository = RoomSleepStageRepository(db.sleepStageDao())
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

    private suspend fun insertStagingRow(
        stage: SleepStage,
        startMs: Long,
        endMs: Long,
        createdAt: Instant,
        driverId: String = "hume-band-1",
    ) {
        db.metricReadingStagingDao().insert(
            stagingRowOf(
                stage = stage,
                recordedAt = Instant.ofEpochMilli(startMs),
                createdAt = createdAt,
                startMs = startMs,
                endMs = endMs,
                driverId = driverId,
            )
        )
    }

    @Test
    fun `promote picks up a staging row whose created_at falls in the sync window even though recorded_at is historical`() = runBlocking {
        // recorded_at: 22:00 the prior night (historical sleep time, decoded from the device).
        // created_at: 07:00 this morning, inside the live sync window (when this sync inserted the row).
        val sleepStart = Instant.parse("2026-07-01T22:00:00Z")
        val sleepEnd = Instant.parse("2026-07-01T22:30:00Z")
        val insertedAt = Instant.parse("2026-07-02T07:00:00Z")
        val syncWindowStartMs = Instant.parse("2026-07-02T06:55:00Z").toEpochMilli()
        val syncWindowEndMs = Instant.parse("2026-07-02T07:05:00Z").toEpochMilli()

        insertStagingRow(SleepStage.DEEP, sleepStart.toEpochMilli(), sleepEnd.toEpochMilli(), insertedAt)

        val pending = stagingRepository.getPendingSleepStages(
            source = DataSource.DEVICE,
            driverId = "hume-band-1",
            syncWindowStartMs = syncWindowStartMs,
            syncWindowEndMs = syncWindowEndMs,
        )
        assertEquals(1, pending.size)

        val expectedDate = LocalDate.parse("2026-07-01")
        val result = promoter.promote("hume-band-1", syncWindowStartMs, syncWindowEndMs)

        val session = sleepRepository.getByDriverAndDate("hume-band-1", expectedDate)
        assertNotNull(session)
        assertEquals(sleepStart, session!!.sleepStartMs)
        assertEquals(sleepEnd, session.sleepEndMs)

        val stages = sleepStageRepository.getStagesForSessionOnce(session.id)
        assertEquals(1, stages.size)
        assertEquals(SleepStage.DEEP, stages[0].stage)

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

        insertStagingRow(SleepStage.DEEP, sleepStart.toEpochMilli(), sleepEnd.toEpochMilli(), previousSyncInsertedAt)

        val pending = stagingRepository.getPendingSleepStages(
            source = DataSource.DEVICE,
            driverId = "hume-band-1",
            syncWindowStartMs = syncWindowStartMs,
            syncWindowEndMs = syncWindowEndMs,
        )
        assertTrue(pending.isEmpty())

        val result = promoter.promote("hume-band-1", syncWindowStartMs, syncWindowEndMs)

        assertNull(sleepRepository.getByDriverAndDate("hume-band-1", LocalDate.parse("2026-07-01")))
        assertEquals(0, result.sessionsCreated)
        assertEquals(emptyList<LocalDate>(), result.datesProcessed)
    }

    @Test
    fun `promote buckets a session by the local, not UTC, calendar date of its wake-end timestamp`() = runBlocking {
        // Fixed-offset zone (no DST), 7 hours behind UTC. A stage ending at local 18:00 on
        // 06-15 is 01:00 UTC on 06-16 — the local and UTC calendar dates of the *end*
        // timestamp disagree, guarding against a regression to ZoneOffset.UTC.
        val localZone = ZoneId.of("Etc/GMT+7")
        TimeZone.setDefault(TimeZone.getTimeZone(localZone))
        val localDate = LocalDate.parse("2026-06-15")
        val utcDateOfEnd = LocalDate.parse("2026-06-16")
        val startMs = LocalDateTime.parse("2026-06-15T17:00:00").atZone(localZone).toInstant().toEpochMilli()
        val endMs = LocalDateTime.parse("2026-06-15T18:00:00").atZone(localZone).toInstant().toEpochMilli()
        val insertedAt = Instant.ofEpochMilli(endMs).plusSeconds(3600)
        val syncWindowStartMs = insertedAt.minusSeconds(300).toEpochMilli()
        val syncWindowEndMs = insertedAt.plusSeconds(300).toEpochMilli()

        insertStagingRow(SleepStage.LIGHT, startMs, endMs, insertedAt)

        val result = promoter.promote("hume-band-1", syncWindowStartMs, syncWindowEndMs)

        assertNotNull(sleepRepository.getByDriverAndDate("hume-band-1", localDate))
        assertNull(sleepRepository.getByDriverAndDate("hume-band-1", utcDateOfEnd))
        assertEquals(listOf(localDate), result.datesProcessed)
    }

    @Test
    fun `promote merges stages before and after midnight from one continuous sleep period into a single session dated by the wake morning`() = runBlocking {
        // FAILING DIRECTION (must not regress): stages before and after local midnight from
        // the same continuous sleep period must land in one session, not two.
        // PASSING DIRECTION: a 23:00-07:00 night is one row, dated by the 07:00 wake date,
        // and querying that row (as DailyDetailViewModel does via
        // getSessionForDate/getStagesForSessionOnce) returns the full, gapless timeline.
        val insertedAt = Instant.parse("2026-07-02T07:05:00Z")
        val syncWindowStartMs = insertedAt.minusSeconds(300).toEpochMilli()
        val syncWindowEndMs = insertedAt.plusSeconds(300).toEpochMilli()

        val stageBounds = listOf(
            SleepStage.LIGHT to ("2026-07-01T23:00:00Z" to "2026-07-01T23:50:00Z"),
            SleepStage.DEEP to ("2026-07-01T23:50:00Z" to "2026-07-02T00:40:00Z"),
            SleepStage.REM to ("2026-07-02T00:40:00Z" to "2026-07-02T02:00:00Z"),
            SleepStage.AWAKE to ("2026-07-02T02:00:00Z" to "2026-07-02T02:10:00Z"),
            SleepStage.LIGHT to ("2026-07-02T02:10:00Z" to "2026-07-02T07:00:00Z"),
        )
        for ((stage, bounds) in stageBounds) {
            insertStagingRow(
                stage = stage,
                startMs = Instant.parse(bounds.first).toEpochMilli(),
                endMs = Instant.parse(bounds.second).toEpochMilli(),
                createdAt = insertedAt,
            )
        }

        val wakeDate = LocalDate.parse("2026-07-02")
        val eveningDate = LocalDate.parse("2026-07-01")
        val result = promoter.promote("hume-band-1", syncWindowStartMs, syncWindowEndMs)

        assertNull(sleepRepository.getByDriverAndDate("hume-band-1", eveningDate))
        val session = sleepRepository.getByDriverAndDate("hume-band-1", wakeDate)
        assertNotNull(session)
        assertEquals(Instant.parse("2026-07-01T23:00:00Z"), session!!.sleepStartMs)
        assertEquals(Instant.parse("2026-07-02T07:00:00Z"), session.sleepEndMs)

        val stages = sleepStageRepository.getStagesForSessionOnce(session.id).sortedBy { it.startMs }
        assertEquals(5, stages.size)
        // Gapless: each stage's end is the next stage's start, all the way to wake time.
        for (i in 0 until stages.size - 1) {
            assertEquals(stages[i].endMs, stages[i + 1].startMs)
        }
        assertEquals(Instant.parse("2026-07-01T23:00:00Z").toEpochMilli(), stages.first().startMs)
        assertEquals(Instant.parse("2026-07-02T07:00:00Z").toEpochMilli(), stages.last().endMs)

        assertEquals(1, result.sessionsCreated)
        assertEquals(listOf(wakeDate), result.datesProcessed)
    }

    @Test
    fun `promote treats an afternoon nap on a different calendar day than the overnight sleep as its own session`() = runBlocking {
        // Edge case: a large internal gap (> 1 hour) splits distinct sleep periods. When the
        // resulting periods fall on different calendar dates, each gets its own session row.
        val insertedAt = Instant.parse("2026-07-02T07:05:00Z")
        val syncWindowStartMs = insertedAt.minusSeconds(300).toEpochMilli()
        val syncWindowEndMs = insertedAt.plusSeconds(300).toEpochMilli()

        val napStart = Instant.parse("2026-07-01T14:00:00Z")
        val napEnd = Instant.parse("2026-07-01T14:30:00Z")
        insertStagingRow(SleepStage.LIGHT, napStart.toEpochMilli(), napEnd.toEpochMilli(), insertedAt)

        val nightStart = Instant.parse("2026-07-01T23:00:00Z")
        val nightMid = Instant.parse("2026-07-02T02:00:00Z")
        val nightEnd = Instant.parse("2026-07-02T07:00:00Z")
        insertStagingRow(SleepStage.DEEP, nightStart.toEpochMilli(), nightMid.toEpochMilli(), insertedAt)
        insertStagingRow(SleepStage.LIGHT, nightMid.toEpochMilli(), nightEnd.toEpochMilli(), insertedAt)

        val napDate = LocalDate.parse("2026-07-01")
        val wakeDate = LocalDate.parse("2026-07-02")
        val result = promoter.promote("hume-band-1", syncWindowStartMs, syncWindowEndMs)

        val napSession = sleepRepository.getByDriverAndDate("hume-band-1", napDate)
        val nightSession = sleepRepository.getByDriverAndDate("hume-band-1", wakeDate)
        assertNotNull(napSession)
        assertNotNull(nightSession)
        assertTrue(napSession!!.id != nightSession!!.id)

        assertEquals(1, sleepStageRepository.getStagesForSessionOnce(napSession.id).size)
        assertEquals(2, sleepStageRepository.getStagesForSessionOnce(nightSession.id).size)

        assertEquals(2, result.sessionsCreated)
        assertEquals(setOf(napDate, wakeDate), result.datesProcessed.toSet())
    }

    @Test
    fun `promote attaches a same-day gap-separated nap to the existing session row for that date`() = runBlocking {
        // Edge case, documented: the sleep_sessions schema allows only one row per
        // (driver_id, date) — see the unique index on SleepSessionEntity. If a large gap
        // splits a same-day nap from the overnight sleep that already woke up on that date,
        // both clusters resolve to the same date and are attached to the SAME row rather
        // than creating a second one. This is an accepted, pre-existing schema constraint,
        // not a defect introduced by this fix.
        val insertedAt = Instant.parse("2026-08-11T13:35:00Z")
        val syncWindowStartMs = insertedAt.minusSeconds(300).toEpochMilli()
        val syncWindowEndMs = insertedAt.plusSeconds(300).toEpochMilli()

        val nightStart = Instant.parse("2026-08-10T23:00:00Z")
        val nightEnd = Instant.parse("2026-08-11T07:00:00Z")
        insertStagingRow(SleepStage.DEEP, nightStart.toEpochMilli(), nightEnd.toEpochMilli(), insertedAt)

        val napStart = Instant.parse("2026-08-11T13:00:00Z")
        val napEnd = Instant.parse("2026-08-11T13:30:00Z")
        insertStagingRow(SleepStage.LIGHT, napStart.toEpochMilli(), napEnd.toEpochMilli(), insertedAt)

        val sharedDate = LocalDate.parse("2026-08-11")
        val result = promoter.promote("hume-band-1", syncWindowStartMs, syncWindowEndMs)

        val session = sleepRepository.getByDriverAndDate("hume-band-1", sharedDate)
        assertNotNull(session)

        val stages = sleepStageRepository.getStagesForSessionOnce(session!!.id)
        assertEquals(2, stages.size)
        assertTrue(stages.any { it.stage == SleepStage.DEEP })
        assertTrue(stages.any { it.stage == SleepStage.LIGHT })

        assertEquals(1, result.sessionsCreated)
        assertEquals(listOf(sharedDate), result.datesProcessed)
    }
}
