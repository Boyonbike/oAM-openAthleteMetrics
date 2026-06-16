package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.RespirationReadingDao
import com.athletedata.openAthleteMetrics.data.db.RespirationReadingEntity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomRespirationReadingRepository @Inject constructor(
    private val dao: RespirationReadingDao,
) : RespirationReadingRepository {

    override suspend fun insert(entity: RespirationReadingEntity) = dao.insert(entity)

    override suspend fun insertAll(entities: List<RespirationReadingEntity>) = dao.insertAll(entities)

    override suspend fun deleteBySource(source: DataSource) = dao.deleteBySource(source)

    override fun getReadingsInRange(startMs: Long, endMs: Long): Flow<List<RespirationReadingEntity>> =
        dao.getReadingsInRange(startMs, endMs)

    override suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<RespirationReadingEntity> =
        dao.getReadingsInRangeOnce(startMs, endMs)

    override fun getLatestReading(): Flow<RespirationReadingEntity?> = dao.getLatestReading()
}
