package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.SleepStageDao
import com.athletedata.openAthleteMetrics.data.db.SleepStageEntity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomSleepStageRepository @Inject constructor(
    private val dao: SleepStageDao,
) : SleepStageRepository {

    override suspend fun insert(entity: SleepStageEntity) = dao.insert(entity)

    override suspend fun insertAll(entities: List<SleepStageEntity>) = dao.insertAll(entities)

    override suspend fun deleteBySource(source: DataSource) = dao.deleteBySource(source)

    override fun getReadingsInRange(startMs: Long, endMs: Long): Flow<List<SleepStageEntity>> =
        dao.getReadingsInRange(startMs, endMs)

    override suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<SleepStageEntity> =
        dao.getReadingsInRangeOnce(startMs, endMs)

    override fun getLatestReading(): Flow<SleepStageEntity?> = dao.getLatestReading()

    override fun getStagesForSession(sessionId: Long): Flow<List<SleepStageEntity>> =
        dao.getStagesForSession(sessionId)

    override suspend fun getStagesForSessionOnce(sessionId: Long): List<SleepStageEntity> =
        dao.getStagesForSessionOnce(sessionId)

    override suspend fun deleteForSession(sessionId: Long) = dao.deleteForSession(sessionId)

    override fun getStagesInRange(startMs: Long, endMs: Long): Flow<List<SleepStageEntity>> =
        dao.getStagesInRange(startMs, endMs)

    override suspend fun insertAllOrIgnore(entities: List<SleepStageEntity>): List<Long> =
        dao.insertAllOrIgnore(entities)
}
