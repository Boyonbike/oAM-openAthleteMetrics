package com.athletedata.openAthleteMetrics.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow

@Dao
interface HrvReadingDao : BaseReadingDao<HrvReadingEntity> {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    override suspend fun insert(entity: HrvReadingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    override suspend fun insertAll(entities: List<HrvReadingEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    override suspend fun insertAllOrIgnore(entities: List<HrvReadingEntity>): List<Long>

    @Query("DELETE FROM hrv_readings WHERE source = :source")
    override suspend fun deleteBySource(source: DataSource)

    @Query("DELETE FROM hrv_readings")
    override suspend fun deleteAll()

    @Query("SELECT * FROM hrv_readings WHERE recorded_at >= :startMs AND recorded_at < :endMs ORDER BY recorded_at ASC")
    override suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<HrvReadingEntity>
}
