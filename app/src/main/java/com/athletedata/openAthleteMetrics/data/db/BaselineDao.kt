package com.athletedata.openAthleteMetrics.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.athletedata.openAthleteMetrics.data.model.BaselineMetric
import kotlinx.coroutines.flow.Flow

@Dao
interface BaselineDao {

    /** Live baseline row for [metric]; emits null if fewer than 14 days of data have been collected. */
    @Query("SELECT * FROM baseline_range WHERE metric_type = :metric LIMIT 1")
    fun observe(metric: BaselineMetric): Flow<BaselineEntity?>

    /** Live stream of all computed baselines. */
    @Query("SELECT * FROM baseline_range")
    fun observeAll(): Flow<List<BaselineEntity>>

    /** Inserts or replaces the baseline for [entity.metricType]. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BaselineEntity)

    // RESET-SYSTEM
    @Query("DELETE FROM baseline_range")
    suspend fun deleteAll()
}
