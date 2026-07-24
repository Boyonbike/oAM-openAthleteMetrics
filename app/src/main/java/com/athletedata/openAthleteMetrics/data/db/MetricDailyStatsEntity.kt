package com.athletedata.openAthleteMetrics.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import com.athletedata.openAthleteMetrics.data.model.BaselineMetric
import java.time.Instant
import java.time.LocalDate

/**
 * Room entity for the `metric_daily_stats` table: one row per (day, [BaselineMetric]) holding a
 * trailing-window mean/std-dev computed by a [com.athletedata.openAthleteMetrics.data.repository.MetricStatsCalculator].
 *
 * Distinct from `SleepAverageCalculator` (on-demand, not persisted) — this table is a per-day
 * history, intended for trend/history views wired up in a later change.
 *
 * @property mean Trailing-window mean; for sleep-stage metrics this is mean minutes. Null when
 * fewer than [minimumDays] in-window days had data — renders as absent, never zero.
 * @property stdDev Population standard deviation (divide by n, not n-1) — null whenever [mean] is null.
 * @property meanPct Average-of-ratios stage percentage; populated only for SLEEP_DEEP/LIGHT/REM/AWAKE, null for all other metrics.
 * @property sampleCount Count of in-window days actually used in [mean]/[stdDev].
 * @property windowDays Resolved trailing-window length (days) used for this row.
 * @property minimumDays Resolved minimum-days-of-data gate used for this row.
 * @property configHash Hash over the resolution inputs (window/minimum tiers) that produced
 * [windowDays]/[minimumDays], so a later config change is detectable.
 * @property computedAt Audit timestamp only — not used for gating or invalidation.
 */
@Entity(tableName = "metric_daily_stats", primaryKeys = ["summary_date", "metric_type"])
data class MetricDailyStatsEntity(
    @ColumnInfo(name = "summary_date")
    val summaryDate: LocalDate,
    @ColumnInfo(name = "metric_type")
    val metricType: BaselineMetric,
    val mean: Double?,
    @ColumnInfo(name = "std_dev")
    val stdDev: Double?,
    @ColumnInfo(name = "mean_pct")
    val meanPct: Double?,
    @ColumnInfo(name = "sample_count")
    val sampleCount: Int,
    @ColumnInfo(name = "window_days")
    val windowDays: Int,
    @ColumnInfo(name = "minimum_days")
    val minimumDays: Int,
    @ColumnInfo(name = "config_hash")
    val configHash: String,
    @ColumnInfo(name = "computed_at")
    val computedAt: Instant,
)
