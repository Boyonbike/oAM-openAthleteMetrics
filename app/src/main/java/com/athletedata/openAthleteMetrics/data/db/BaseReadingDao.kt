package com.athletedata.openAthleteMetrics.data.db

import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow

interface BaseReadingDao<T> {
    suspend fun insert(entity: T)
    suspend fun insertAll(entities: List<T>)
    suspend fun insertAllOrIgnore(entities: List<T>): List<Long>
    suspend fun deleteBySource(source: DataSource)
    suspend fun deleteAll()
    suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<T>
}
