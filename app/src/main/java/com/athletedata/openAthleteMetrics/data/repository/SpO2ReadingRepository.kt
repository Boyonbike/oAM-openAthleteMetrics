package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.SpO2ReadingEntity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow

interface SpO2ReadingRepository {
    suspend fun insert(entity: SpO2ReadingEntity)
    suspend fun insertAll(entities: List<SpO2ReadingEntity>)
    suspend fun insertAllOrIgnore(entities: List<SpO2ReadingEntity>): List<Long>
    suspend fun deleteBySource(source: DataSource)
    fun getReadingsInRange(startMs: Long, endMs: Long): Flow<List<SpO2ReadingEntity>>
    suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<SpO2ReadingEntity>
    fun getLatestReading(): Flow<SpO2ReadingEntity?>
}
