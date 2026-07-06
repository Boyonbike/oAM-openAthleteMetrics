package com.athletedata.openAthleteMetrics.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.athletedata.openAthleteMetrics.data.model.BaselineMetric
import kotlinx.coroutines.flow.Flow

@Dao
interface BaselineWindowConfigDao {

    /** Live override row for [metric]; null if no override is active. */
    @Query("SELECT * FROM baseline_window_config WHERE metric_type = :metric LIMIT 1")
    fun observe(metric: BaselineMetric): Flow<BaselineWindowConfigEntity?>

    /** Live stream of all override rows. */
    @Query("SELECT * FROM baseline_window_config")
    fun observeAll(): Flow<List<BaselineWindowConfigEntity>>

    /** Inserts or replaces the override row for [entity.metricType]. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BaselineWindowConfigEntity)

    /** Removes [metric]'s override row, reverting it to fallback/default resolution. */
    @Query("DELETE FROM baseline_window_config WHERE metric_type = :metric")
    suspend fun delete(metric: BaselineMetric)
}
