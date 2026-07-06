package com.athletedata.openAthleteMetrics.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.athletedata.openAthleteMetrics.data.model.BaselineMetric

/**
 * Room entity for the `baseline_window_config` table.
 *
 * A row for a [BaselineMetric] means an explicit override is active for that metric.
 * The two value columns are independently nullable: a row may override the window,
 * the minimum, or both — a null column falls through to the next resolution tier
 * (fallback default, then global Settings) exactly as if no row existed for that
 * field. See [com.athletedata.openAthleteMetrics.data.repository.BaselineWindowConfigRepository]
 * for the full resolution order. This table is the only place override values live;
 * a future Settings screen reads/writes it directly with no other data-layer changes.
 */
@Entity(tableName = "baseline_window_config")
data class BaselineWindowConfigEntity(
    @PrimaryKey
    @ColumnInfo(name = "metric_type")
    val metricType: BaselineMetric,
    @ColumnInfo(name = "window_days")
    val windowDays: Int?,
    @ColumnInfo(name = "minimum_days")
    val minimumDays: Int?,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
