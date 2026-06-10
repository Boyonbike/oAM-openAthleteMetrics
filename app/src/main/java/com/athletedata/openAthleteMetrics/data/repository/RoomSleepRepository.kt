package com.athletedata.openAthleteMetrics.data.repository

import androidx.work.WorkManager
import com.athletedata.openAthleteMetrics.data.db.SleepSessionDao
import com.athletedata.openAthleteMetrics.data.db.toEntity
import com.athletedata.openAthleteMetrics.data.db.toModel
import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.data.model.SleepSession
import com.athletedata.openAthleteMetrics.worker.enqueueSummaryWorker
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

    override suspend fun insertFromDevice(session: SleepSession): Int {
        try {
            val rowId = dao.insertOrIgnore(
                session.copy(source = DataSource.DEVICE).toEntity()
            )
            return if (rowId == -1L) 1 else 0
        } catch (e: Exception) {
            Timber.e(e, "Failed to insert device sleep session")
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
