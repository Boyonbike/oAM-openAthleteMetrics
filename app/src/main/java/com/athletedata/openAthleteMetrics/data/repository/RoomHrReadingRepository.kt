package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.HrReadingDao
import com.athletedata.openAthleteMetrics.data.db.HrReadingEntity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomHrReadingRepository @Inject constructor(
    private val dao: HrReadingDao,
) : HrReadingRepository {

    override suspend fun insert(entity: HrReadingEntity) = dao.insert(entity)

    override suspend fun insertAll(entities: List<HrReadingEntity>) = dao.insertAll(entities)

    override suspend fun deleteBySource(source: DataSource) = dao.deleteBySource(source)

    override fun getReadingsInRange(startMs: Long, endMs: Long): Flow<List<HrReadingEntity>> =
        dao.getReadingsInRange(startMs, endMs)

    override suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<HrReadingEntity> =
        dao.getReadingsInRangeOnce(startMs, endMs)

    override fun getLatestReading(): Flow<HrReadingEntity?> = dao.getLatestReading()

    override fun hasSeederDataForDate(date: LocalDate): Flow<Boolean> {
        val startMs = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val endMs = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return dao.countSourceDataInRange(DataSource.SEEDER, startMs, endMs).map { it > 0 }
    }
}
