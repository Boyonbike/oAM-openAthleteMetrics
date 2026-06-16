package com.athletedata.openAthleteMetrics.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow

@Dao
interface BloodPressureReadingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: BloodPressureReadingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<BloodPressureReadingEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllOrIgnore(entities: List<BloodPressureReadingEntity>): List<Long>

    @Query("DELETE FROM blood_pressure_readings WHERE source = :source")
    suspend fun deleteBySource(source: DataSource)

    @Query("DELETE FROM blood_pressure_readings")
    suspend fun deleteAll()

    @Query("SELECT * FROM blood_pressure_readings WHERE recorded_at >= :startMs AND recorded_at < :endMs ORDER BY recorded_at ASC")
    fun getReadingsInRange(startMs: Long, endMs: Long): Flow<List<BloodPressureReadingEntity>>

    @Query("SELECT * FROM blood_pressure_readings WHERE recorded_at >= :startMs AND recorded_at < :endMs ORDER BY recorded_at ASC")
    suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<BloodPressureReadingEntity>

    @Query("SELECT * FROM blood_pressure_readings ORDER BY recorded_at DESC LIMIT 1")
    fun getLatestReading(): Flow<BloodPressureReadingEntity?>
}
