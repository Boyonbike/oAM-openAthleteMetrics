package com.athletedata.openAthleteMetrics.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow

@Dao
interface SleepStageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SleepStageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<SleepStageEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllOrIgnore(entities: List<SleepStageEntity>): List<Long>

    @Query("DELETE FROM sleep_stages WHERE source = :source")
    suspend fun deleteBySource(source: DataSource)

    @Query("DELETE FROM sleep_stages")
    suspend fun deleteAll()

    @Query("SELECT * FROM sleep_stages WHERE start_ms >= :startMs AND start_ms < :endMs ORDER BY start_ms ASC")
    fun getReadingsInRange(startMs: Long, endMs: Long): Flow<List<SleepStageEntity>>

    @Query("SELECT * FROM sleep_stages WHERE start_ms >= :startMs AND start_ms < :endMs ORDER BY start_ms ASC")
    suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<SleepStageEntity>

    @Query("SELECT * FROM sleep_stages ORDER BY start_ms DESC LIMIT 1")
    fun getLatestReading(): Flow<SleepStageEntity?>

    @Query("SELECT * FROM sleep_stages WHERE session_id = :sessionId ORDER BY start_ms ASC")
    fun getStagesForSession(sessionId: Long): Flow<List<SleepStageEntity>>

    @Query("SELECT * FROM sleep_stages WHERE session_id = :sessionId ORDER BY start_ms ASC")
    suspend fun getStagesForSessionOnce(sessionId: Long): List<SleepStageEntity>

    @Query("DELETE FROM sleep_stages WHERE session_id = :sessionId")
    suspend fun deleteForSession(sessionId: Long)

    @Query("SELECT * FROM sleep_stages WHERE start_ms >= :startMs AND start_ms < :endMs ORDER BY start_ms ASC")
    fun getStagesInRange(startMs: Long, endMs: Long): Flow<List<SleepStageEntity>>
}
