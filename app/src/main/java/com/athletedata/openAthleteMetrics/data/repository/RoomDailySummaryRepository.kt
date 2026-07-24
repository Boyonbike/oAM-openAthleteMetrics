package com.athletedata.openAthleteMetrics.data.repository

import android.content.Context
import com.athletedata.openAthleteMetrics.data.db.DailySummaryDao
import com.athletedata.openAthleteMetrics.data.db.toEntity
import com.athletedata.openAthleteMetrics.data.db.toModel
import com.athletedata.openAthleteMetrics.data.model.DailySummary
import com.athletedata.openAthleteMetrics.glance.MetricGlanceWidget
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomDailySummaryRepository @Inject constructor(
    private val dao: DailySummaryDao,
    @ApplicationContext private val context: Context,
) : DailySummaryRepository {

    override suspend fun upsert(summary: DailySummary) {
        try {
            dao.upsert(summary.toEntity())
        } catch (e: Exception) {
            Timber.e(e, "Failed to upsert daily summary")
            throw e
        }
        // Home-screen widgets only ever show "today" - an upsert for a past date (historical
        // resync/seeding) can't change what's rendered, so skip the refresh in that case.
        if (summary.date == LocalDate.now()) {
            MetricGlanceWidget().updateAll(context)
        }
    }

    override fun getSummaryForDate(date: LocalDate): Flow<DailySummary?> =
        dao.getSummaryForDate(date).map { it?.toModel() }

    override fun getSummariesForRange(from: LocalDate, to: LocalDate): Flow<List<DailySummary>> =
        dao.getSummariesForRange(from, to).map { entities -> entities.map { it.toModel() } }

    override suspend fun getSummaryForDateOnce(date: LocalDate): DailySummary? =
        dao.getSummaryForDateOnce(date)?.toModel()

    override suspend fun getLatestDateOnce(): LocalDate? = dao.getLatestDateOnce()

    override suspend fun deleteAll() {
        try {
            dao.deleteAll()
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete all daily summaries")
            throw e
        }
    }
}
