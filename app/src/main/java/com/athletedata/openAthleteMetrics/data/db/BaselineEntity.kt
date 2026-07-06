package com.athletedata.openAthleteMetrics.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.athletedata.openAthleteMetrics.data.model.BaselineMetric

/**
 * Room entity for the `baseline_range` table.
 *
 * One row per [BaselineMetric], written by [com.athletedata.openAthleteMetrics.data.repository.RoomBaselineRepository.recalculate]
 * on-demand after DailySummaryWorker completes. Absence of a row means fewer
 * than 14 days of data have been collected yet — no baseline should display.
 *
 * TODO: when database export/import is built, include baseline_range —
 * "baselines travel with the backup" (Master Plan).
 */
@Entity(tableName = "baseline_range")
data class BaselineEntity(
    @PrimaryKey
    @ColumnInfo(name = "metric_type")
    val metricType: BaselineMetric,
    val mean: Double,
    @ColumnInfo(name = "std_dev")
    val stdDev: Double,
    val lower: Double,
    val upper: Double,
    @ColumnInfo(name = "window_days")
    val windowDays: Int,
    @ColumnInfo(name = "calculated_at")
    val calculatedAt: Long,
)
