package com.athletedata.app.data.repository

import com.athletedata.app.data.db.DailySummaryDao
import com.athletedata.app.data.db.toEntity
import com.athletedata.app.data.db.toModel
import com.athletedata.app.data.model.DailySummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomDailySummaryRepository @Inject constructor(
    private val dao: DailySummaryDao,
) : DailySummaryRepository {

    /**
     * Inserts or replaces the pre-computed summary for [summary.date].
     * Called exclusively by DailySummaryWorker after aggregating raw readings.
     */
    override suspend fun upsert(summary: DailySummary) {
        dao.upsert(summary.toEntity())
    }

    /**
     * Live stream of the summary for [date].
     * Observed by the Daily Overview ViewModel — emits null until the worker runs.
     */
    override fun getSummaryForDate(date: LocalDate): Flow<DailySummary?> =
        dao.getSummaryForDate(date).map { it?.toModel() }

    /**
     * Live stream of summaries in [[from], [to]] inclusive, oldest first.
     * Observed by metric detail charts that show a 30/90-day trend.
     */
    override fun getSummariesForRange(from: LocalDate, to: LocalDate): Flow<List<DailySummary>> =
        dao.getSummariesForRange(from, to).map { entities -> entities.map { it.toModel() } }

    /**
     * One-shot read of the existing summary before the worker overwrites it.
     * Called by DailySummaryWorker to avoid re-computing unchanged values.
     */
    override suspend fun getSummaryForDateOnce(date: LocalDate): DailySummary? =
        dao.getSummaryForDateOnce(date)?.toModel()

    /**
     * Deletes all summary rows.
     * Called after seeder data is cleared so stale computed rows don't persist.
     */
    override suspend fun deleteAll() {
        dao.deleteAll()
    }
}
