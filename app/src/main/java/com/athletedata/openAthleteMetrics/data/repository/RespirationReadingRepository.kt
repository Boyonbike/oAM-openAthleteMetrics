package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.RespirationReadingEntity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow

interface RespirationReadingRepository {
    suspend fun insert(entity: RespirationReadingEntity)
    suspend fun insertAll(entities: List<RespirationReadingEntity>)
    suspend fun deleteBySource(source: DataSource)
    fun getReadingsInRange(startMs: Long, endMs: Long): Flow<List<RespirationReadingEntity>>
    suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<RespirationReadingEntity>
    fun getLatestReading(): Flow<RespirationReadingEntity?>
}
