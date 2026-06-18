package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.BloodPressureReadingDao
import com.athletedata.openAthleteMetrics.data.db.BloodPressureReadingEntity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomBloodPressureReadingRepository @Inject constructor(
    private val dao: BloodPressureReadingDao,
) : BloodPressureReadingRepository {

    override suspend fun insert(entity: BloodPressureReadingEntity) {
        try {
            dao.insert(entity)
        } catch (e: Exception) {
            Timber.e(e, "Failed to insert blood pressure reading")
            throw e
        }
    }

    override suspend fun insertAll(entities: List<BloodPressureReadingEntity>) {
        try {
            dao.insertAll(entities)
        } catch (e: Exception) {
            Timber.e(e, "Failed to insert all blood pressure readings")
            throw e
        }
    }

    override suspend fun insertAllOrIgnore(entities: List<BloodPressureReadingEntity>): List<Long> {
        return try {
            dao.insertAllOrIgnore(entities)
        } catch (e: Exception) {
            Timber.e(e, "Failed to insert all blood pressure readings (or-ignore)")
            throw e
        }
    }

    override suspend fun deleteBySource(source: DataSource) {
        try {
            dao.deleteBySource(source)
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete blood pressure readings by source")
            throw e
        }
    }

    override fun getReadingsInRange(startMs: Long, endMs: Long): Flow<List<BloodPressureReadingEntity>> =
        dao.getReadingsInRange(startMs, endMs)

    override suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<BloodPressureReadingEntity> =
        dao.getReadingsInRangeOnce(startMs, endMs)

    override fun getLatestReading(): Flow<BloodPressureReadingEntity?> = dao.getLatestReading()
}
