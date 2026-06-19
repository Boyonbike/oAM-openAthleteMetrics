package com.athletedata.openAthleteMetrics.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow

@Dao
interface SpO2ReadingDao : BaseReadingDao<SpO2ReadingEntity> {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    override suspend fun insert(entity: SpO2ReadingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    override suspend fun insertAll(entities: List<SpO2ReadingEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    override suspend fun insertAllOrIgnore(entities: List<SpO2ReadingEntity>): List<Long>

    @Query("DELETE FROM spo2_readings WHERE source = :source")
    override suspend fun deleteBySource(source: DataSource)

    @Query("DELETE FROM spo2_readings")
    override suspend fun deleteAll()

    @Query("SELECT * FROM spo2_readings WHERE recorded_at >= :startMs AND recorded_at < :endMs ORDER BY recorded_at ASC")
    override suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<SpO2ReadingEntity>
}
