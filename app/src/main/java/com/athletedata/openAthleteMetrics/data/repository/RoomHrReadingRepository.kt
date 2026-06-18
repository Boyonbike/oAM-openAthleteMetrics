package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.HrReadingDao
import com.athletedata.openAthleteMetrics.data.db.HrReadingEntity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import com.athletedata.openAthleteMetrics.data.db.toUtcStartMs
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomHrReadingRepository @Inject constructor(
    private val dao: HrReadingDao,
) : HrReadingRepository {

    override suspend fun insert(entity: HrReadingEntity) {
        try {
            dao.insert(entity)
        } catch (e: Exception) {
            Timber.e(e, "Failed to insert HR reading")
            throw e
        }
    }

    override suspend fun insertAll(entities: List<HrReadingEntity>) {
        try {
            dao.insertAll(entities)
        } catch (e: Exception) {
            Timber.e(e, "Failed to insert all HR readings")
            throw e
        }
    }

    override suspend fun insertAllOrIgnore(entities: List<HrReadingEntity>): List<Long> {
        return try {
            dao.insertAllOrIgnore(entities)
        } catch (e: Exception) {
            Timber.e(e, "Failed to insert all HR readings (or-ignore)")
            throw e
        }
    }

    override suspend fun deleteBySource(source: DataSource) {
        try {
            dao.deleteBySource(source)
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete HR readings by source")
            throw e
        }
    }

    override fun getReadingsInRange(startMs: Long, endMs: Long): Flow<List<HrReadingEntity>> =
        dao.getReadingsInRange(startMs, endMs)

    override suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<HrReadingEntity> =
        dao.getReadingsInRangeOnce(startMs, endMs)

    override fun getLatestReading(): Flow<HrReadingEntity?> = dao.getLatestReading()

    override fun hasSeederDataForDate(date: LocalDate): Flow<Boolean> {
        val startMs = date.toUtcStartMs()
        val endMs = date.plusDays(1).toUtcStartMs()
        return dao.countSourceDataInRange(DataSource.SEEDER, startMs, endMs).map { it > 0 }
    }
}
