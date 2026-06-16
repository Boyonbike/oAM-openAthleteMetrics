package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.SleepStageEntity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow

interface SleepStageRepository {
    suspend fun insert(entity: SleepStageEntity)
    suspend fun insertAll(entities: List<SleepStageEntity>)
    suspend fun deleteBySource(source: DataSource)
    fun getReadingsInRange(startMs: Long, endMs: Long): Flow<List<SleepStageEntity>>
    suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<SleepStageEntity>
    fun getLatestReading(): Flow<SleepStageEntity?>
    fun getStagesForSession(sessionId: Long): Flow<List<SleepStageEntity>>
    suspend fun getStagesForSessionOnce(sessionId: Long): List<SleepStageEntity>
    suspend fun deleteForSession(sessionId: Long)
    fun getStagesInRange(startMs: Long, endMs: Long): Flow<List<SleepStageEntity>>
}
