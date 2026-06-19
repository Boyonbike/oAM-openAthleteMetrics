package com.athletedata.openAthleteMetrics.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow

@Dao
interface RespirationReadingDao : BaseReadingDao<RespirationReadingEntity> {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    override suspend fun insert(entity: RespirationReadingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    override suspend fun insertAll(entities: List<RespirationReadingEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    override suspend fun insertAllOrIgnore(entities: List<RespirationReadingEntity>): List<Long>

    @Query("DELETE FROM respiration_readings WHERE source = :source")
    override suspend fun deleteBySource(source: DataSource)

    @Query("DELETE FROM respiration_readings")
    override suspend fun deleteAll()

    @Query("SELECT * FROM respiration_readings WHERE recorded_at >= :startMs AND recorded_at < :endMs ORDER BY recorded_at ASC")
    override suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<RespirationReadingEntity>
}
