package com.athletedata.openAthleteMetrics.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow

@Dao
interface HrReadingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: HrReadingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<HrReadingEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllOrIgnore(entities: List<HrReadingEntity>): List<Long>

    @Query("DELETE FROM hr_readings WHERE source = :source")
    suspend fun deleteBySource(source: DataSource)

    @Query("DELETE FROM hr_readings")
    suspend fun deleteAll()

    @Query("SELECT * FROM hr_readings WHERE recorded_at >= :startMs AND recorded_at < :endMs ORDER BY recorded_at ASC")
    fun getReadingsInRange(startMs: Long, endMs: Long): Flow<List<HrReadingEntity>>

    @Query("SELECT * FROM hr_readings WHERE recorded_at >= :startMs AND recorded_at < :endMs ORDER BY recorded_at ASC")
    suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<HrReadingEntity>

    @Query("SELECT * FROM hr_readings ORDER BY recorded_at DESC LIMIT 1")
    fun getLatestReading(): Flow<HrReadingEntity?>

    @Query(
        "SELECT COUNT(*) FROM hr_readings " +
        "WHERE source = :source AND recorded_at >= :startMs AND recorded_at < :endMs"
    )
    fun countSourceDataInRange(source: DataSource, startMs: Long, endMs: Long): Flow<Int>
}
