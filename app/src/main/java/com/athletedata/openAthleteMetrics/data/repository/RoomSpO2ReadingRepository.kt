package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.SpO2ReadingDao
import com.athletedata.openAthleteMetrics.data.db.SpO2ReadingEntity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomSpO2ReadingRepository @Inject constructor(
    private val dao: SpO2ReadingDao,
) : SpO2ReadingRepository {

    override suspend fun insert(entity: SpO2ReadingEntity) = dao.insert(entity)

    override suspend fun insertAll(entities: List<SpO2ReadingEntity>) = dao.insertAll(entities)

    override suspend fun deleteBySource(source: DataSource) = dao.deleteBySource(source)

    override fun getReadingsInRange(startMs: Long, endMs: Long): Flow<List<SpO2ReadingEntity>> =
        dao.getReadingsInRange(startMs, endMs)

    override suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<SpO2ReadingEntity> =
        dao.getReadingsInRangeOnce(startMs, endMs)

    override fun getLatestReading(): Flow<SpO2ReadingEntity?> = dao.getLatestReading()
}
