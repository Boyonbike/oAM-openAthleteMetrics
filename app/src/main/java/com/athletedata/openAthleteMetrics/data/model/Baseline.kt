package com.athletedata.openAthleteMetrics.data.model

/**
 * Metrics eligible for a computed baseline range. Deliberately distinct from
 * [MetricType] (which models raw BLE reading routing and has no SLEEP value —
 * sleep duration comes from daily_summary, not a routed reading type).
 *
 * HR/HRV/RHR/SLEEP/SPO2/STEPS plus the four sleep-stage metrics (SLEEP_DEEP/LIGHT/REM/AWAKE)
 * are all part of the mean±SD baseline-band system: each is computed into `metric_daily_stats`
 * (mean/std_dev) by a [com.athletedata.openAthleteMetrics.data.repository.MetricStatsCalculator].
 * SLEEP_ONSET/SLEEP_WAKE are the two deferred exceptions — they stay on the live
 * [com.athletedata.openAthleteMetrics.data.repository.SleepAverageCalculator] as a
 * time-of-day average rather than a numeric mean/std_dev fit, so they never populate a
 * `metric_daily_stats` row.
 */
enum class BaselineMetric {
    HR, HRV, RHR, SLEEP, SPO2, STEPS,
    SLEEP_DEEP, SLEEP_LIGHT, SLEEP_REM, SLEEP_AWAKE, SLEEP_ONSET, SLEEP_WAKE,
}

/** Whether a [BaselineRange] was computed automatically or set explicitly by the user. */
enum class BaselineSource { AUTO, MANUAL }

/** A metric's normal range: mean ± 1 standard deviation over the configured rolling window. */
data class BaselineRange(
    val lower: Double,
    val upper: Double,
    val source: BaselineSource,
)
