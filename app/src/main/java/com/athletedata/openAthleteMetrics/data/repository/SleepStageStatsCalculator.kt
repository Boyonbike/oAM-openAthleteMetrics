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
 * [MetricStatsCalculator] for the four sleep-stage metrics (SLEEP_DEEP/LIGHT/REM/AWAKE). One
 * instance per metric, differing only in [stageSelector] (see `CalculatorModule`'s `@IntoMap`
 * bindings).
 *
 * Re-implements — deliberately does not call — the per-night average-of-ratios algorithm from
 * [SleepAverageCalculator.averageStage] (that file is out of scope to modify this session): for
 * each night with both a non-null stage value and a positive sum of that same night's four stage
 * fields, the night's ratio is stage/night-total*100; the final [MetricDailyStatsEntity.meanPct]
 * is the average of those per-night ratios (average-of-ratios), not the ratio of averaged sums.
 * If the ratio semantics in [SleepAverageCalculator] ever change, this must be updated to match
 * by hand — there is no shared code between the two calculators.
 *
 * Unlike [SleepAverageCalculator]'s truncated-to-Int display value, [MetricDailyStatsEntity.mean]
 * here keeps full double precision since it's a persisted stat, not a formatted UI string.
 */
class SleepStageStatsCalculator(
    private val metric: BaselineMetric,
    private val summaryRepo: DailySummaryRepository,
    private val windowConfigRepo: BaselineWindowConfigRepository,
    private val stageSelector: (DailySummary) -> Int?,
) : MetricStatsCalculator {

    override suspend fun compute(date: LocalDate, zone: ZoneId): MetricDailyStatsEntity {
        val windowDays = windowConfigRepo.getEffectiveWindow(metric)
        val minimumDays = windowConfigRepo.getEffectiveMinimum(metric)
        val rangeStart = date.minusDays((windowDays - 1).toLong())

        val summaries = summaryRepo.getSummariesForRange(rangeStart, date).first()
        val nights = summaries.mapNotNull { nightStageRatio(it, stageSelector) }

        val mean: Double?
        val stdDev: Double?
        val meanPct: Double?
        if (nights.size >= minimumDays) {
            val minutes = nights.map { it.first }
            val m = minutes.average()
            // Population variance (divide by n, not n-1).
            val variance = minutes.sumOf { (it - m) * (it - m) } / minutes.size
            mean = m
            stdDev = sqrt(variance)
            meanPct = nights.map { it.second }.average()
        } else {
            mean = null
            stdDev = null
            meanPct = null
        }

        return MetricDailyStatsEntity(
            summaryDate = date,
            metricType = metric,
            mean = mean,
            stdDev = stdDev,
            meanPct = meanPct,
            sampleCount = nights.size,
            windowDays = windowDays,
            minimumDays = minimumDays,
            configHash = MetricStatsConfigHash.compute(metric, windowDays, minimumDays, windowConfigRepo),
            computedAt = Instant.now(),
        )
    }

    /**
     * Denominator is this same night's own four stage fields, not the separately sourced
     * `summary.sleepMinutes` — guarantees stage/total can never exceed 100%, mirroring
     * [SleepAverageCalculator.averageStage]'s reasoning exactly.
     */
    private fun nightStageRatio(summary: DailySummary, stageSelector: (DailySummary) -> Int?): Pair<Double, Double>? {
        val stage = stageSelector(summary) ?: return null
        val total = listOfNotNull(
            summary.sleepDeepMinutes, summary.sleepLightMinutes,
            summary.sleepRemMinutes, summary.sleepAwakeMinutes,
        ).sum()
        if (total <= 0) return null
        return stage.toDouble() to (stage * 100.0 / total)
    }
}
