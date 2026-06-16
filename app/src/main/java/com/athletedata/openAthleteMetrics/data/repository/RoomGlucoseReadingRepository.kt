package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.GlucoseReadingDao
import com.athletedata.openAthleteMetrics.data.db.GlucoseReadingEntity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomGlucoseReadingRepository @Inject constructor(
    private val dao: GlucoseReadingDao,
) : GlucoseReadingRepository {

    override suspend fun insert(entity: GlucoseReadingEntity) = dao.insert(entity)

    override suspend fun insertAll(entities: List<GlucoseReadingEntity>) = dao.insertAll(entities)

    override suspend fun deleteBySource(source: DataSource) = dao.deleteBySource(source)

    override fun getReadingsInRange(startMs: Long, endMs: Long): Flow<List<GlucoseReadingEntity>> =
        dao.getReadingsInRange(startMs, endMs)

    override suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<GlucoseReadingEntity> =
        dao.getReadingsInRangeOnce(startMs, endMs)

    override fun getLatestReading(): Flow<GlucoseReadingEntity?> = dao.getLatestReading()
}
