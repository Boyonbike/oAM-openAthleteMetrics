package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.ActiveCalorieReadingDao
import com.athletedata.openAthleteMetrics.data.db.ActiveCalorieReadingEntity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomActiveCalorieReadingRepository @Inject constructor(
    private val dao: ActiveCalorieReadingDao,
) : ActiveCalorieReadingRepository {

    override suspend fun insert(entity: ActiveCalorieReadingEntity) {
        try {
            dao.insert(entity)
        } catch (e: Exception) {
            Timber.e(e, "Failed to insert active calorie reading")
            throw e
        }
    }

    override suspend fun insertAll(entities: List<ActiveCalorieReadingEntity>) {
        try {
            dao.insertAll(entities)
        } catch (e: Exception) {
            Timber.e(e, "Failed to insert all active calorie readings")
            throw e
        }
    }

    override suspend fun insertAllOrIgnore(entities: List<ActiveCalorieReadingEntity>): List<Long> {
        return try {
            dao.insertAllOrIgnore(entities)
        } catch (e: Exception) {
            Timber.e(e, "Failed to insert all active calorie readings (or-ignore)")
            throw e
        }
    }

    override suspend fun deleteBySource(source: DataSource) {
        try {
            dao.deleteBySource(source)
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete active calorie readings by source")
            throw e
        }
    }

    override fun getReadingsInRange(startMs: Long, endMs: Long): Flow<List<ActiveCalorieReadingEntity>> =
        dao.getReadingsInRange(startMs, endMs)

    override suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<ActiveCalorieReadingEntity> =
        dao.getReadingsInRangeOnce(startMs, endMs)

    override fun getLatestReading(): Flow<ActiveCalorieReadingEntity?> = dao.getLatestReading()
}
