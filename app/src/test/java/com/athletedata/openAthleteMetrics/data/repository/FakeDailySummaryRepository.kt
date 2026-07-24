package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.DailySummaryDao
import com.athletedata.openAthleteMetrics.data.db.toEntity
import com.athletedata.openAthleteMetrics.data.db.toModel
import com.athletedata.openAthleteMetrics.data.model.DailySummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/**
 * Test-only [DailySummaryRepository] backed by a real [DailySummaryDao] — so the actual SQL
 * predicates in `getSummariesForRange` (the trailing-window boundary) are exercised, the same way
 * [SleepStagePromoterTest][com.athletedata.openAthleteMetrics.worker.SleepStagePromoterTest] wires
 * real Room-backed repositories — without the production `RoomDailySummaryRepository`'s Android
 * Context / Glance-widget-refresh side effect, which is unrelated to the calculator logic under
 * test.
 */
class FakeDailySummaryRepository(private val dao: DailySummaryDao) : DailySummaryRepository {

    override suspend fun upsert(summary: DailySummary) {
        dao.upsert(summary.toEntity())
    }

    override fun getSummaryForDate(date: LocalDate): Flow<DailySummary?> =
        dao.getSummaryForDate(date).map { it?.toModel() }

    override fun getSummariesForRange(from: LocalDate, to: LocalDate): Flow<List<DailySummary>> =
        dao.getSummariesForRange(from, to).map { entities -> entities.map { it.toModel() } }

    override suspend fun getSummaryForDateOnce(date: LocalDate): DailySummary? =
        dao.getSummaryForDateOnce(date)?.toModel()

    override suspend fun getLatestDateOnce(): LocalDate? = dao.getLatestDateOnce()

    override suspend fun deleteAll() {
        dao.deleteAll()
    }
}
