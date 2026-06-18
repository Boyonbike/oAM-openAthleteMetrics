package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.HrvReadingEntity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow

interface HrvReadingRepository {
    suspend fun insert(entity: HrvReadingEntity)
    suspend fun insertAll(entities: List<HrvReadingEntity>)
    suspend fun insertAllOrIgnore(entities: List<HrvReadingEntity>): List<Long>
    suspend fun deleteBySource(source: DataSource)
    fun getReadingsInRange(startMs: Long, endMs: Long): Flow<List<HrvReadingEntity>>
    suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<HrvReadingEntity>
    fun getLatestReading(): Flow<HrvReadingEntity?>
}
