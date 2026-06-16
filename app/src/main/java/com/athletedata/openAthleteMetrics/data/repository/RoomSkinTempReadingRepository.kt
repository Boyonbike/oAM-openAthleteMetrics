package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.SkinTempReadingDao
import com.athletedata.openAthleteMetrics.data.db.SkinTempReadingEntity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomSkinTempReadingRepository @Inject constructor(
    private val dao: SkinTempReadingDao,
) : SkinTempReadingRepository {

    override suspend fun insert(entity: SkinTempReadingEntity) = dao.insert(entity)

    override suspend fun insertAll(entities: List<SkinTempReadingEntity>) = dao.insertAll(entities)

    override suspend fun deleteBySource(source: DataSource) = dao.deleteBySource(source)

    override fun getReadingsInRange(startMs: Long, endMs: Long): Flow<List<SkinTempReadingEntity>> =
        dao.getReadingsInRange(startMs, endMs)

    override suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<SkinTempReadingEntity> =
        dao.getReadingsInRangeOnce(startMs, endMs)

    override fun getLatestReading(): Flow<SkinTempReadingEntity?> = dao.getLatestReading()
}
