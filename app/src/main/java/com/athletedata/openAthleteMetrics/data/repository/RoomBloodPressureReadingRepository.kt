package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.BloodPressureReadingDao
import com.athletedata.openAthleteMetrics.data.db.BloodPressureReadingEntity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomBloodPressureReadingRepository @Inject constructor(
    private val dao: BloodPressureReadingDao,
) : BloodPressureReadingRepository {

    override suspend fun insert(entity: BloodPressureReadingEntity) = dao.insert(entity)

    override suspend fun insertAll(entities: List<BloodPressureReadingEntity>) = dao.insertAll(entities)

    override suspend fun deleteBySource(source: DataSource) = dao.deleteBySource(source)

    override fun getReadingsInRange(startMs: Long, endMs: Long): Flow<List<BloodPressureReadingEntity>> =
        dao.getReadingsInRange(startMs, endMs)

    override suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<BloodPressureReadingEntity> =
        dao.getReadingsInRangeOnce(startMs, endMs)

    override fun getLatestReading(): Flow<BloodPressureReadingEntity?> = dao.getLatestReading()
}
