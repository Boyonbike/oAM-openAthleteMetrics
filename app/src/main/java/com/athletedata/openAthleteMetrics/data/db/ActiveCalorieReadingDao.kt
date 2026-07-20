package com.athletedata.openAthleteMetrics.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow

@Dao
interface ActiveCalorieReadingDao : BaseReadingDao<ActiveCalorieReadingEntity> {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    override suspend fun insert(entity: ActiveCalorieReadingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    override suspend fun insertAll(entities: List<ActiveCalorieReadingEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    override suspend fun insertAllOrIgnore(entities: List<ActiveCalorieReadingEntity>): List<Long>

    @Query("DELETE FROM active_calorie_readings WHERE source = :source")
    override suspend fun deleteBySource(source: DataSource)

    @Query("DELETE FROM active_calorie_readings")
    override suspend fun deleteAll()

    @Query("SELECT * FROM active_calorie_readings WHERE recorded_at >= :startMs AND recorded_at < :endMs ORDER BY recorded_at ASC")
    override suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<ActiveCalorieReadingEntity>

    // CALORIES-MODE: used by ABSOLUTE mode to remove stale same-day readings before inserting the
    // latest. Scoped by driver_id in addition to source so that syncing one driver's ABSOLUTE
    // readings for a day doesn't delete another driver's device-sourced readings for that day.
    @Query("DELETE FROM active_calorie_readings WHERE source = :source AND driver_id = :driverId AND recorded_at >= :startMs AND recorded_at < :endMs")
    suspend fun deleteBySourceForDay(source: DataSource, driverId: String?, startMs: Long, endMs: Long) // CALORIES-MODE
}
