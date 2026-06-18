package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.SleepStageDao
import com.athletedata.openAthleteMetrics.data.db.SleepStageEntity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomSleepStageRepository @Inject constructor(
    private val dao: SleepStageDao,
) : SleepStageRepository {

    override suspend fun insert(entity: SleepStageEntity) {
        try {
            dao.insert(entity)
        } catch (e: Exception) {
            Timber.e(e, "Failed to insert sleep stage")
            throw e
        }
    }

    override suspend fun insertAll(entities: List<SleepStageEntity>) {
        try {
            dao.insertAll(entities)
        } catch (e: Exception) {
            Timber.e(e, "Failed to insert all sleep stages")
            throw e
        }
    }

    override suspend fun deleteBySource(source: DataSource) {
        try {
            dao.deleteBySource(source)
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete sleep stages by source")
            throw e
        }
    }

    override fun getReadingsInRange(startMs: Long, endMs: Long): Flow<List<SleepStageEntity>> =
        dao.getReadingsInRange(startMs, endMs)

    override suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<SleepStageEntity> =
        dao.getReadingsInRangeOnce(startMs, endMs)

    override fun getLatestReading(): Flow<SleepStageEntity?> = dao.getLatestReading()

    override fun getStagesForSession(sessionId: Long): Flow<List<SleepStageEntity>> =
        dao.getStagesForSession(sessionId)

    override suspend fun getStagesForSessionOnce(sessionId: Long): List<SleepStageEntity> =
        dao.getStagesForSessionOnce(sessionId)

    override suspend fun deleteForSession(sessionId: Long) {
        try {
            dao.deleteForSession(sessionId)
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete sleep stages for session")
            throw e
        }
    }

    override suspend fun insertAllOrIgnore(entities: List<SleepStageEntity>): List<Long> {
        return try {
            dao.insertAllOrIgnore(entities)
        } catch (e: Exception) {
            Timber.e(e, "Failed to insert-or-ignore sleep stages")
            throw e
        }
    }
}
