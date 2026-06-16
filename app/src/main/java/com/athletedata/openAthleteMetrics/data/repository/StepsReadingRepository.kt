package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.StepsReadingEntity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow

interface StepsReadingRepository {
    suspend fun insert(entity: StepsReadingEntity)
    suspend fun insertAll(entities: List<StepsReadingEntity>)
    suspend fun deleteBySource(source: DataSource)
    fun getReadingsInRange(startMs: Long, endMs: Long): Flow<List<StepsReadingEntity>>
    suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<StepsReadingEntity>
    fun getLatestReading(): Flow<StepsReadingEntity?>
}
