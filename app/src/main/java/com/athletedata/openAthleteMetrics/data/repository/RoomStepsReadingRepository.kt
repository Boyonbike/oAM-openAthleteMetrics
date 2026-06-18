package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.StepsReadingDao
import com.athletedata.openAthleteMetrics.data.db.StepsReadingEntity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomStepsReadingRepository @Inject constructor(
    private val dao: StepsReadingDao,
) : StepsReadingRepository {

    override suspend fun insert(entity: StepsReadingEntity) {
        try {
            dao.insert(entity)
        } catch (e: Exception) {
            Timber.e(e, "Failed to insert steps reading")
            throw e
        }
    }

    override suspend fun insertAll(entities: List<StepsReadingEntity>) {
        try {
            dao.insertAll(entities)
        } catch (e: Exception) {
            Timber.e(e, "Failed to insert all steps readings")
            throw e
        }
    }

    override suspend fun insertAllOrIgnore(entities: List<StepsReadingEntity>): List<Long> {
        return try {
            dao.insertAllOrIgnore(entities)
        } catch (e: Exception) {
            Timber.e(e, "Failed to insert all steps readings (or-ignore)")
            throw e
        }
    }

    override suspend fun deleteBySource(source: DataSource) {
        try {
            dao.deleteBySource(source)
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete steps readings by source")
            throw e
        }
    }

    override fun getReadingsInRange(startMs: Long, endMs: Long): Flow<List<StepsReadingEntity>> =
        dao.getReadingsInRange(startMs, endMs)

    override suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<StepsReadingEntity> =
        dao.getReadingsInRangeOnce(startMs, endMs)

    override fun getLatestReading(): Flow<StepsReadingEntity?> = dao.getLatestReading()
}
