package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.HrReadingEntity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

interface HrReadingRepository {
    suspend fun insert(entity: HrReadingEntity)
    suspend fun insertAll(entities: List<HrReadingEntity>)
    suspend fun deleteBySource(source: DataSource)
    fun getReadingsInRange(startMs: Long, endMs: Long): Flow<List<HrReadingEntity>>
    suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<HrReadingEntity>
    fun getLatestReading(): Flow<HrReadingEntity?>
    fun hasSeederDataForDate(date: LocalDate): Flow<Boolean>
}
