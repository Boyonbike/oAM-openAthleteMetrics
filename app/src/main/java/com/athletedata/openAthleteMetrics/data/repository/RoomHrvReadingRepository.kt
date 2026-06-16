package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.HrvReadingDao
import com.athletedata.openAthleteMetrics.data.db.HrvReadingEntity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomHrvReadingRepository @Inject constructor(
    private val dao: HrvReadingDao,
) : HrvReadingRepository {

    override suspend fun insert(entity: HrvReadingEntity) = dao.insert(entity)

    override suspend fun insertAll(entities: List<HrvReadingEntity>) = dao.insertAll(entities)

    override suspend fun deleteBySource(source: DataSource) = dao.deleteBySource(source)

    override fun getReadingsInRange(startMs: Long, endMs: Long): Flow<List<HrvReadingEntity>> =
        dao.getReadingsInRange(startMs, endMs)

    override suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<HrvReadingEntity> =
        dao.getReadingsInRangeOnce(startMs, endMs)

    override fun getLatestReading(): Flow<HrvReadingEntity?> = dao.getLatestReading()
}
