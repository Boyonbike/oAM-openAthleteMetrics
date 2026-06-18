package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.GlucoseReadingDao
import com.athletedata.openAthleteMetrics.data.db.GlucoseReadingEntity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomGlucoseReadingRepository @Inject constructor(
    private val dao: GlucoseReadingDao,
) : GlucoseReadingRepository {

    override suspend fun insert(entity: GlucoseReadingEntity) {
        try {
            dao.insert(entity)
        } catch (e: Exception) {
            Timber.e(e, "Failed to insert glucose reading")
            throw e
        }
    }

    override suspend fun insertAll(entities: List<GlucoseReadingEntity>) {
        try {
            dao.insertAll(entities)
        } catch (e: Exception) {
            Timber.e(e, "Failed to insert all glucose readings")
            throw e
        }
    }

    override suspend fun insertAllOrIgnore(entities: List<GlucoseReadingEntity>): List<Long> {
        return try {
            dao.insertAllOrIgnore(entities)
        } catch (e: Exception) {
            Timber.e(e, "Failed to insert all glucose readings (or-ignore)")
            throw e
        }
    }

    override suspend fun deleteBySource(source: DataSource) {
        try {
            dao.deleteBySource(source)
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete glucose readings by source")
            throw e
        }
    }

    override fun getReadingsInRange(startMs: Long, endMs: Long): Flow<List<GlucoseReadingEntity>> =
        dao.getReadingsInRange(startMs, endMs)

    override suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<GlucoseReadingEntity> =
        dao.getReadingsInRangeOnce(startMs, endMs)

    override fun getLatestReading(): Flow<GlucoseReadingEntity?> = dao.getLatestReading()
}
