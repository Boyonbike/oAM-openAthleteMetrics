package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.SkinTempReadingEntity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow

interface SkinTempReadingRepository {
    suspend fun insert(entity: SkinTempReadingEntity)
    suspend fun insertAll(entities: List<SkinTempReadingEntity>)
    suspend fun deleteBySource(source: DataSource)
    fun getReadingsInRange(startMs: Long, endMs: Long): Flow<List<SkinTempReadingEntity>>
    suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<SkinTempReadingEntity>
    fun getLatestReading(): Flow<SkinTempReadingEntity?>
}
