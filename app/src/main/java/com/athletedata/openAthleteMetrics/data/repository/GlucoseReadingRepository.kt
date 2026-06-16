package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.GlucoseReadingEntity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow

interface GlucoseReadingRepository {
    suspend fun insert(entity: GlucoseReadingEntity)
    suspend fun insertAll(entities: List<GlucoseReadingEntity>)
    suspend fun deleteBySource(source: DataSource)
    fun getReadingsInRange(startMs: Long, endMs: Long): Flow<List<GlucoseReadingEntity>>
    suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<GlucoseReadingEntity>
    fun getLatestReading(): Flow<GlucoseReadingEntity?>
}
