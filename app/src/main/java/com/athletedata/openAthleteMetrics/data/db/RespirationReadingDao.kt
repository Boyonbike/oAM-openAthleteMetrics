package com.athletedata.openAthleteMetrics.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow

@Dao
interface RespirationReadingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: RespirationReadingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<RespirationReadingEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllOrIgnore(entities: List<RespirationReadingEntity>): List<Long>

    @Query("DELETE FROM respiration_readings WHERE source = :source")
    suspend fun deleteBySource(source: DataSource)

    @Query("DELETE FROM respiration_readings")
    suspend fun deleteAll()

    @Query("SELECT * FROM respiration_readings WHERE recorded_at >= :startMs AND recorded_at < :endMs ORDER BY recorded_at ASC")
    fun getReadingsInRange(startMs: Long, endMs: Long): Flow<List<RespirationReadingEntity>>

    @Query("SELECT * FROM respiration_readings WHERE recorded_at >= :startMs AND recorded_at < :endMs ORDER BY recorded_at ASC")
    suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<RespirationReadingEntity>

    @Query("SELECT * FROM respiration_readings ORDER BY recorded_at DESC LIMIT 1")
    fun getLatestReading(): Flow<RespirationReadingEntity?>
}
