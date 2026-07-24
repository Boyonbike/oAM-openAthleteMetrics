package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.SleepSessionDao
import com.athletedata.openAthleteMetrics.data.db.toEntity
import com.athletedata.openAthleteMetrics.data.db.toModel
import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.data.model.SleepSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate

/**
 * Test-only [SleepRepository] backed by a real [SleepSessionDao] — same rationale as
 * [FakeDailySummaryRepository]: exercises the real SQL predicates without [RoomSleepRepository]'s
 * `WorkManager` side effect, which is unrelated to the read-path logic under test.
 */
class FakeSleepRepository(private val dao: SleepSessionDao) : SleepRepository {

    override suspend fun insert(session: SleepSession) {
        dao.insert(session.toEntity())
    }

    override suspend fun insertFromDevice(session: SleepSession): Int {
        val rowId = dao.insertOrIgnore(session.copy(source = DataSource.DEVICE).toEntity())
        return if (rowId == -1L) 1 else 0
    }

    override suspend fun insertOrReplace(session: SleepSession) {
        dao.insert(session.copy(source = DataSource.DEVICE).toEntity())
    }

    override suspend fun updateSessionSpan(
        id: Long,
        date: LocalDate,
        sleepStartMs: Instant,
        sleepEndMs: Instant,
        durationMinutes: Int,
    ) {
        dao.updateSpan(id, date, sleepStartMs, sleepEndMs, durationMinutes)
    }

    override fun getSessionForDate(date: LocalDate): Flow<SleepSession?> =
        dao.getSessionForDate(date).map { it?.toModel() }

    override fun getSessionsForRange(from: LocalDate, to: LocalDate): Flow<List<SleepSession>> =
        dao.getSessionsForRange(from, to).map { entities -> entities.map { it.toModel() } }

    override suspend fun getSessionForDateOnce(date: LocalDate): SleepSession? =
        dao.getSessionForDateOnce(date)?.toModel()

    override suspend fun getByDriverAndDate(driverId: String, date: LocalDate): SleepSession? =
        dao.getByDriverAndDate(driverId, date)?.toModel()

    override suspend fun getSessionsForDriverInRange(driverId: String, from: LocalDate, to: LocalDate): List<SleepSession> =
        dao.getByDriverAndDateRange(driverId, from, to).map { it.toModel() }

    override suspend fun deleteBySource(source: DataSource) {
        dao.deleteBySource(source)
    }

    override suspend fun deleteAll() {
        dao.deleteAll()
    }
}
