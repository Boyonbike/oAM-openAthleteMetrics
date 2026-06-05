package com.athletedata.app.data.repository

import com.athletedata.app.data.db.SleepSessionDao
import com.athletedata.app.data.db.toEntity
import com.athletedata.app.data.db.toModel
import com.athletedata.app.data.model.DataSource
import com.athletedata.app.data.model.SleepSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomSleepRepository @Inject constructor(
    private val dao: SleepSessionDao,
) : SleepRepository {

    /**
     * Inserts or replaces the session. Source must be set by the caller.
     * Called by the device driver (source=DEVICE) and the seeder (source=SEEDER).
     * TODO: trigger DailySummaryWorker for session.date after the worker is created.
     */
    override suspend fun insert(session: SleepSession) {
        dao.insert(session.toEntity())
    }

    /**
     * Live stream of the sleep session for [date] (the morning the sleeper woke up).
     * Observed by the Daily Overview sleep card and the Sleep detail screen.
     */
    override fun getSessionForDate(date: LocalDate): Flow<SleepSession?> =
        dao.getSessionForDate(date).map { it?.toModel() }

    /**
     * Live stream of sessions in [[from], [to]] inclusive, oldest first.
     * Observed by the Sleep history page and the metric range chart.
     */
    override fun getSessionsForRange(from: LocalDate, to: LocalDate): Flow<List<SleepSession>> =
        dao.getSessionsForRange(from, to).map { entities -> entities.map { it.toModel() } }

    /**
     * One-shot read of the session for [date]; no Flow overhead.
     * Called by DailySummaryWorker to read sleep duration before computing the summary.
     */
    override suspend fun getSessionForDateOnce(date: LocalDate): SleepSession? =
        dao.getSessionForDateOnce(date)?.toModel()

    /**
     * Deletes all sessions with the given [source].
     * Called by the debug seeder cleanup with source=SEEDER.
     */
    override suspend fun deleteBySource(source: DataSource) {
        dao.deleteBySource(source)
    }
}
