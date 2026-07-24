package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.MetricDailyStatsDao
import com.athletedata.openAthleteMetrics.data.model.BaselineMetric
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * Computes and upserts [com.athletedata.openAthleteMetrics.data.db.MetricDailyStatsEntity] rows
 * for a given day, shared by [com.athletedata.openAthleteMetrics.worker.DailySummaryWorker]'s
 * per-day hook and [com.athletedata.openAthleteMetrics.worker.MetricStatsRecomputeWorker]'s
 * backfill/config-invalidation recompute, so the compute+upsert loop isn't duplicated.
 */
class MetricDailyStatsWriter @Inject constructor(
    private val dao: MetricDailyStatsDao,
    private val calculators: Map<BaselineMetric, @JvmSuppressWildcards MetricStatsCalculator>,
) {

    /**
     * Computes and upserts [date]'s row for each of [metrics] (default: every in-scope metric).
     * [zone] must be the same zone the caller resolved [date] against — never re-derived here.
     *
     * A single metric's calculator failing is logged and skipped, not rethrown: it must not block
     * the other metrics in the same batch, nor abort a caller (e.g. DailySummaryWorker) whose more
     * important write (the daily_summary upsert) has already succeeded.
     */
    suspend fun write(
        date: LocalDate,
        zone: ZoneId,
        metrics: Collection<BaselineMetric> = calculators.keys,
    ) {
        for (metric in metrics) {
            val calculator = calculators[metric] ?: continue
            try {
                calculator.compute(date, zone)?.let { dao.upsert(it) }
            } catch (e: Exception) {
                Timber.e(e, "metric_daily_stats compute failed metric=%s date=%s", metric, date)
            }
        }
    }
}
