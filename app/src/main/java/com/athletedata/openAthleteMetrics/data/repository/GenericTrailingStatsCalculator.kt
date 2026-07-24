package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.MetricDailyStatsEntity
import com.athletedata.openAthleteMetrics.data.model.BaselineMetric
import com.athletedata.openAthleteMetrics.data.model.DailySummary
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.sqrt

/**
 * Default [MetricStatsCalculator] for the six metrics whose stat is a plain trailing-window
 * mean/std-dev of a single `daily_summary` column: HR, HRV, RHR, SLEEP, SPO2, STEPS. One instance
 * per metric, differing only in [valueSelector] (see `CalculatorModule`'s `@IntoMap` bindings).
 *
 * [date] is not converted through [zone] — `daily_summary` rows are already keyed by calendar
 * date, so no instant-to-day bucketing is needed here; [zone] exists on the interface for
 * calculators that do need it.
 */
class GenericTrailingStatsCalculator(
    private val metric: BaselineMetric,
    private val summaryRepo: DailySummaryRepository,
    private val windowConfigRepo: BaselineWindowConfigRepository,
    private val valueSelector: (DailySummary) -> Double?,
) : MetricStatsCalculator {

    override suspend fun compute(date: LocalDate, zone: ZoneId): MetricDailyStatsEntity {
        val windowDays = windowConfigRepo.getEffectiveWindow(metric)
        val minimumDays = windowConfigRepo.getEffectiveMinimum(metric)
        val rangeStart = date.minusDays((windowDays - 1).toLong())

        val values = summaryRepo.getSummariesForRange(rangeStart, date).first().mapNotNull(valueSelector)

        // Population variance (divide by n, not n-1).
        val (mean, stdDev) = if (values.size >= minimumDays) {
            val m = values.average()
            val variance = values.sumOf { (it - m) * (it - m) } / values.size
            m to sqrt(variance)
        } else {
            null to null
        }

        return MetricDailyStatsEntity(
            summaryDate = date,
            metricType = metric,
            mean = mean,
            stdDev = stdDev,
            meanPct = null,
            sampleCount = values.size,
            windowDays = windowDays,
            minimumDays = minimumDays,
            configHash = MetricStatsConfigHash.compute(metric, windowDays, minimumDays, windowConfigRepo),
            computedAt = Instant.now(),
        )
    }
}
