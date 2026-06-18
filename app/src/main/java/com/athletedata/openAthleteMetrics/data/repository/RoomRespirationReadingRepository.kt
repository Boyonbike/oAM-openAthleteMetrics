package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.RespirationReadingDao
import com.athletedata.openAthleteMetrics.data.db.RespirationReadingEntity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomRespirationReadingRepository @Inject constructor(
    private val dao: RespirationReadingDao,
) : RespirationReadingRepository {

    override suspend fun insert(entity: RespirationReadingEntity) {
        try {
            dao.insert(entity)
        } catch (e: Exception) {
            Timber.e(e, "Failed to insert respiration reading")
            throw e
        }
    }

    override suspend fun insertAll(entities: List<RespirationReadingEntity>) {
        try {
            dao.insertAll(entities)
        } catch (e: Exception) {
            Timber.e(e, "Failed to insert all respiration readings")
            throw e
        }
    }

    override suspend fun insertAllOrIgnore(entities: List<RespirationReadingEntity>): List<Long> {
        return try {
            dao.insertAllOrIgnore(entities)
        } catch (e: Exception) {
            Timber.e(e, "Failed to insert all respiration readings (or-ignore)")
            throw e
        }
    }

    override suspend fun deleteBySource(source: DataSource) {
        try {
            dao.deleteBySource(source)
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete respiration readings by source")
            throw e
        }
    }

    override fun getReadingsInRange(startMs: Long, endMs: Long): Flow<List<RespirationReadingEntity>> =
        dao.getReadingsInRange(startMs, endMs)

    override suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<RespirationReadingEntity> =
        dao.getReadingsInRangeOnce(startMs, endMs)

    override fun getLatestReading(): Flow<RespirationReadingEntity?> = dao.getLatestReading()
}
