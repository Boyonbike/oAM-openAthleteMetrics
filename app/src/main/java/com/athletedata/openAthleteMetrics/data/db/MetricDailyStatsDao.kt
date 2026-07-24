package com.athletedata.openAthleteMetrics.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.athletedata.openAthleteMetrics.data.model.BaselineMetric
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface MetricDailyStatsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(stats: MetricDailyStatsEntity)

    @Query("SELECT * FROM metric_daily_stats WHERE metric_type = :metric AND summary_date = :date")
    fun observe(metric: BaselineMetric, date: LocalDate): Flow<MetricDailyStatsEntity?>

    @Query(
        "SELECT * FROM metric_daily_stats WHERE metric_type = :metric AND summary_date BETWEEN :from AND :to ORDER BY summary_date ASC",
    )
    fun getRange(metric: BaselineMetric, from: LocalDate, to: LocalDate): Flow<List<MetricDailyStatsEntity>>

    /** One-shot read of every stored row for [metric], regardless of date — used to find stale-hash rows on config change. */
    @Query("SELECT * FROM metric_daily_stats WHERE metric_type = :metric")
    suspend fun getAllForMetricOnce(metric: BaselineMetric): List<MetricDailyStatsEntity>

    @Query("DELETE FROM metric_daily_stats WHERE metric_type = :metric")
    suspend fun deleteByMetric(metric: BaselineMetric)

    @Query("DELETE FROM metric_daily_stats")
    suspend fun deleteAll()
}
