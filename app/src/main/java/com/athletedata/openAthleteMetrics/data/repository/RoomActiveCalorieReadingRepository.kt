package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.ActiveCalorieReadingDao
import com.athletedata.openAthleteMetrics.data.db.ActiveCalorieReadingEntity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomActiveCalorieReadingRepository @Inject constructor(
    private val dao: ActiveCalorieReadingDao,
) : ActiveCalorieReadingRepository {

    override suspend fun insert(entity: ActiveCalorieReadingEntity) = dao.insert(entity)

    override suspend fun insertAll(entities: List<ActiveCalorieReadingEntity>) = dao.insertAll(entities)

    override suspend fun deleteBySource(source: DataSource) = dao.deleteBySource(source)

    override fun getReadingsInRange(startMs: Long, endMs: Long): Flow<List<ActiveCalorieReadingEntity>> =
        dao.getReadingsInRange(startMs, endMs)

    override suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<ActiveCalorieReadingEntity> =
        dao.getReadingsInRangeOnce(startMs, endMs)

    override fun getLatestReading(): Flow<ActiveCalorieReadingEntity?> = dao.getLatestReading()
}
