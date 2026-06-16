package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.TotalCalorieReadingDao
import com.athletedata.openAthleteMetrics.data.db.TotalCalorieReadingEntity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomTotalCalorieReadingRepository @Inject constructor(
    private val dao: TotalCalorieReadingDao,
) : TotalCalorieReadingRepository {

    override suspend fun insert(entity: TotalCalorieReadingEntity) = dao.insert(entity)

    override suspend fun insertAll(entities: List<TotalCalorieReadingEntity>) = dao.insertAll(entities)

    override suspend fun deleteBySource(source: DataSource) = dao.deleteBySource(source)

    override fun getReadingsInRange(startMs: Long, endMs: Long): Flow<List<TotalCalorieReadingEntity>> =
        dao.getReadingsInRange(startMs, endMs)

    override suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<TotalCalorieReadingEntity> =
        dao.getReadingsInRangeOnce(startMs, endMs)

    override fun getLatestReading(): Flow<TotalCalorieReadingEntity?> = dao.getLatestReading()
}
