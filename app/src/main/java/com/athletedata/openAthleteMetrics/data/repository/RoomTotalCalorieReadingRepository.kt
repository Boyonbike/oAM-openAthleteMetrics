package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.TotalCalorieReadingDao
import com.athletedata.openAthleteMetrics.data.db.TotalCalorieReadingEntity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomTotalCalorieReadingRepository @Inject constructor(
    private val dao: TotalCalorieReadingDao,
) : TotalCalorieReadingRepository {

    override suspend fun insert(entity: TotalCalorieReadingEntity) {
        try {
            dao.insert(entity)
        } catch (e: Exception) {
            Timber.e(e, "Failed to insert total calorie reading")
            throw e
        }
    }

    override suspend fun insertAll(entities: List<TotalCalorieReadingEntity>) {
        try {
            dao.insertAll(entities)
        } catch (e: Exception) {
            Timber.e(e, "Failed to insert all total calorie readings")
            throw e
        }
    }

    override suspend fun insertAllOrIgnore(entities: List<TotalCalorieReadingEntity>): List<Long> {
        return try {
            dao.insertAllOrIgnore(entities)
        } catch (e: Exception) {
            Timber.e(e, "Failed to insert all total calorie readings (or-ignore)")
            throw e
        }
    }

    override suspend fun deleteBySource(source: DataSource) {
        try {
            dao.deleteBySource(source)
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete total calorie readings by source")
            throw e
        }
    }

    override fun getReadingsInRange(startMs: Long, endMs: Long): Flow<List<TotalCalorieReadingEntity>> =
        dao.getReadingsInRange(startMs, endMs)

    override suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<TotalCalorieReadingEntity> =
        dao.getReadingsInRangeOnce(startMs, endMs)

    override fun getLatestReading(): Flow<TotalCalorieReadingEntity?> = dao.getLatestReading()
}
