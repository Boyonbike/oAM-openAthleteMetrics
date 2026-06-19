package com.athletedata.openAthleteMetrics.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow

@Dao
interface HrReadingDao : BaseReadingDao<HrReadingEntity> {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    override suspend fun insert(entity: HrReadingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    override suspend fun insertAll(entities: List<HrReadingEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    override suspend fun insertAllOrIgnore(entities: List<HrReadingEntity>): List<Long>

    @Query("DELETE FROM hr_readings WHERE source = :source")
    override suspend fun deleteBySource(source: DataSource)

    @Query("DELETE FROM hr_readings")
    override suspend fun deleteAll()

    @Query("SELECT * FROM hr_readings WHERE recorded_at >= :startMs AND recorded_at < :endMs ORDER BY recorded_at ASC")
    override suspend fun getReadingsInRangeOnce(startMs: Long, endMs: Long): List<HrReadingEntity>

    @Query(
        "SELECT COUNT(*) FROM hr_readings " +
        "WHERE source = :source AND recorded_at >= :startMs AND recorded_at < :endMs"
    )
    fun countSourceDataInRange(source: DataSource, startMs: Long, endMs: Long): Flow<Int>
}
