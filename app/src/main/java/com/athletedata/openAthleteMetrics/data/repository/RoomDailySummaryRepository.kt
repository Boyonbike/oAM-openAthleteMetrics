package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.DailySummaryDao
import com.athletedata.openAthleteMetrics.data.db.toEntity
import com.athletedata.openAthleteMetrics.data.db.toModel
import com.athletedata.openAthleteMetrics.data.model.DailySummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomDailySummaryRepository @Inject constructor(
    private val dao: DailySummaryDao,
) : DailySummaryRepository {

    override suspend fun upsert(summary: DailySummary) {
        try {
            dao.upsert(summary.toEntity())
        } catch (e: Exception) {
            Timber.e(e, "Failed to upsert daily summary")
            throw e
        }
    }

    override fun getSummaryForDate(date: LocalDate): Flow<DailySummary?> =
        dao.getSummaryForDate(date).map { it?.toModel() }

    override fun getSummariesForRange(from: LocalDate, to: LocalDate): Flow<List<DailySummary>> =
        dao.getSummariesForRange(from, to).map { entities -> entities.map { it.toModel() } }

    override suspend fun getSummaryForDateOnce(date: LocalDate): DailySummary? =
        dao.getSummaryForDateOnce(date)?.toModel()

    override suspend fun deleteAll() {
        try {
            dao.deleteAll()
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete all daily summaries")
            throw e
        }
    }
}
