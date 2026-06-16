package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.TotalCalorieReadingEntity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow

interface TotalCalorieReadingRepository {
    suspend fun insert(entity: TotalCalorieReadingEntity)
    suspend fun insertAll(entities: List<TotalCalorieReadingEntity>)
    suspend fun deleteBySource(source: DataSource)
    fun getReadingsInRange(startMs: Long, endMs: Long): Flow<List<TotalCalorieReadingEntity>>
    suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<TotalCalorieReadingEntity>
    fun getLatestReading(): Flow<TotalCalorieReadingEntity?>
}
