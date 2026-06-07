package com.athletedata.app.data.repository

import androidx.work.WorkManager
import com.athletedata.app.data.db.SleepSessionDao
import com.athletedata.app.data.db.toEntity
import com.athletedata.app.data.db.toModel
import com.athletedata.app.data.model.DataSource
import com.athletedata.app.data.model.SleepSession
import com.athletedata.app.worker.enqueueSummaryWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomSleepRepository @Inject constructor(
    private val dao: SleepSessionDao,
    private val workManager: WorkManager,
) : SleepRepository {

    override suspend fun insert(session: SleepSession) {
        try {
            dao.insert(session.toEntity())
            enqueueSummaryWorker(session.date, workManager)
        } catch (e: Exception) {
            Timber.e(e, "Failed to insert sleep session")
            throw e
        }
    }

    override fun getSessionForDate(date: LocalDate): Flow<SleepSession?> =
        dao.getSessionForDate(date).map { it?.toModel() }

    override fun getSessionsForRange(from: LocalDate, to: LocalDate): Flow<List<SleepSession>> =
        dao.getSessionsForRange(from, to).map { entities -> entities.map { it.toModel() } }

    override suspend fun getSessionForDateOnce(date: LocalDate): SleepSession? =
        dao.getSessionForDateOnce(date)?.toModel()

    override suspend fun deleteBySource(source: DataSource) {
        try {
            dao.deleteBySource(source)
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete sleep sessions by source")
            throw e
        }
    }
}
