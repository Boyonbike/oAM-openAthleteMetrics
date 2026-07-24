package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.MetricDailyStatsEntity
import java.time.LocalDate
import java.time.ZoneId

/**
 * Strategy for producing one [MetricDailyStatsEntity] row for a single anchor day.
 *
 * Bound one-per-[com.athletedata.openAthleteMetrics.data.model.BaselineMetric] via a Hilt
 * multibound map (`@IntoMap @BaselineMetricKey`), so each metric's row can evolve its own
 * calculation independently (e.g. a future metric-specific rework) without touching the others.
 */
interface MetricStatsCalculator {

    /**
     * Computes the row for [date]. [zone] is provided for calculators that need to convert
     * instants to calendar days; implementations must use it (or [ZoneId.systemDefault] when
     * no zone is supplied by a caller) rather than hardcoding [java.time.ZoneOffset.UTC].
     *
     * Returns null only if this metric has nothing to say for [date] at all; a day within scope
     * but below the minimum-days gate still returns a row with [MetricDailyStatsEntity.mean] null.
     */
    suspend fun compute(date: LocalDate, zone: ZoneId): MetricDailyStatsEntity?
}
