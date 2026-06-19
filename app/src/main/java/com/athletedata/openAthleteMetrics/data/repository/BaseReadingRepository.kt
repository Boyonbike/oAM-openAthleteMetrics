package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow

interface BaseReadingRepository<T> {
    suspend fun insert(entity: T)
    suspend fun insertAll(entities: List<T>)
    suspend fun insertAllOrIgnore(entities: List<T>): List<Long>
    suspend fun deleteBySource(source: DataSource)
    suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<T>
}
