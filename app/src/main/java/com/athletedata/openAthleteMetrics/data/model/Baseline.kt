package com.athletedata.openAthleteMetrics.data.model

/**
 * Metrics eligible for a computed baseline range. Deliberately distinct from
 * [MetricType] (which models raw BLE reading routing and has no SLEEP value —
 * sleep duration comes from daily_summary, not a routed reading type).
 *
 * SLEEP_DEEP/SLEEP_LIGHT/SLEEP_REM/SLEEP_AWAKE/SLEEP_ONSET/SLEEP_WAKE exist only to let
 * [com.athletedata.openAthleteMetrics.data.repository.SleepAverageCalculator] reuse the
 * window/minimum resolution in [com.athletedata.openAthleteMetrics.data.repository.BaselineWindowConfigRepository]
 * for plain display averages — they are not part of the mean±SD baseline-band system
 * (see [RoomBaselineRepository.valueFor] no-op branch).
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
