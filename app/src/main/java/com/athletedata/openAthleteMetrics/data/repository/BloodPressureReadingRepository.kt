package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.BloodPressureReadingEntity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow

interface BloodPressureReadingRepository {
    suspend fun insert(entity: BloodPressureReadingEntity)
    suspend fun insertAll(entities: List<BloodPressureReadingEntity>)
    suspend fun deleteBySource(source: DataSource)
    fun getReadingsInRange(startMs: Long, endMs: Long): Flow<List<BloodPressureReadingEntity>>
    suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<BloodPressureReadingEntity>
    fun getLatestReading(): Flow<BloodPressureReadingEntity?>
}
