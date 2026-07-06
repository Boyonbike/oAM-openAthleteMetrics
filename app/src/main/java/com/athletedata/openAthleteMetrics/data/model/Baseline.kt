package com.athletedata.openAthleteMetrics.data.model

/**
 * Metrics eligible for a computed baseline range. Deliberately distinct from
 * [MetricType] (which models raw BLE reading routing and has no SLEEP value —
 * sleep duration comes from daily_summary, not a routed reading type).
 */
enum class BaselineMetric { HR, HRV, RHR, SLEEP, SPO2, STEPS }

/** Whether a [BaselineRange] was computed automatically or set explicitly by the user. */
enum class BaselineSource { AUTO, MANUAL }

/** A metric's normal range: mean ± 1 standard deviation over the configured rolling window. */
data class BaselineRange(
    val lower: Double,
    val upper: Double,
    val source: BaselineSource,
)
