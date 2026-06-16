package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.ActiveCalorieReadingEntity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow

interface ActiveCalorieReadingRepository {
    suspend fun insert(entity: ActiveCalorieReadingEntity)
    suspend fun insertAll(entities: List<ActiveCalorieReadingEntity>)
    suspend fun deleteBySource(source: DataSource)
    fun getReadingsInRange(startMs: Long, endMs: Long): Flow<List<ActiveCalorieReadingEntity>>
    suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<ActiveCalorieReadingEntity>
    fun getLatestReading(): Flow<ActiveCalorieReadingEntity?>
}
