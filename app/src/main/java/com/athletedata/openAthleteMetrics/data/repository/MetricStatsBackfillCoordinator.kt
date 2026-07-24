package com.athletedata.openAthleteMetrics.data.repository

import androidx.work.WorkManager
import com.athletedata.openAthleteMetrics.data.db.MetricDailyStatsDao
import com.athletedata.openAthleteMetrics.data.model.BaselineMetric
import com.athletedata.openAthleteMetrics.worker.enqueueMetricStatsRecompute
import java.time.LocalDate
import javax.inject.Inject

/**
 * Orchestrates `metric_daily_stats` recompute for the two cases that aren't the plain per-day
 * write ([MetricDailyStatsWriter], called directly from
 * [com.athletedata.openAthleteMetrics.worker.DailySummaryWorker]):
 *
 * 1. **Backfill span** — a change to a date `d`'s source data invalidates every stored row `J`
 *    whose trailing window still includes `d`. Window length differs per metric, so the span is
 *    computed per metric and unioned per date before enqueueing.
 * 2. **Config-change invalidation** — a metric's window/minimum override changes its
 *    [MetricStatsConfigHash]; every stored row whose hash no longer matches is stale and gets
 *    recomputed, scoped to that one metric.
 *
 * Both enqueue [com.athletedata.openAthleteMetrics.worker.MetricStatsRecomputeWorker] rather than
 * re-running the full [com.athletedata.openAthleteMetrics.worker.DailySummaryWorker] — the
 * underlying `daily_summary` rows for the affected dates haven't changed, only the trailing
 * stats derived from them, so there's no need to re-aggregate raw readings.
 */
class MetricStatsBackfillCoordinator @Inject constructor(
    private val dailySummaryRepository: DailySummaryRepository,
    private val windowConfigRepository: BaselineWindowConfigRepository,
    private val metricDailyStatsDao: MetricDailyStatsDao,
    private val calculators: Map<BaselineMetric, @JvmSuppressWildcards MetricStatsCalculator>,
    private val workManager: WorkManager,
) {

    /**
     * For each `d` in [changedDates], invalidates every in-scope metric's rows in
     * `[d, min(latest, d + windowDays - 1)]` (that metric's own resolved window), then enqueues
     * one recompute per resulting date with exactly the metrics whose window reaches it.
     *
     * Bounded by the most recent date with a `daily_summary` row ([DailySummaryRepository.getLatestDateOnce])
     * — never [java.time.LocalDate.now] — so this never schedules work for dates with no data yet.
     */
    suspend fun enqueueSpanRecompute(changedDates: Set<LocalDate>) {
        if (changedDates.isEmpty()) return
        val latest = dailySummaryRepository.getLatestDateOnce() ?: return

        val dateMetrics = mutableMapOf<LocalDate, MutableSet<BaselineMetric>>()
        for (metric in calculators.keys) {
            val windowDays = windowConfigRepository.getEffectiveWindow(metric)
            for (d in changedDates) {
                val spanEnd = minOf(latest, d.plusDays((windowDays - 1).toLong()))
                var cur = d
                while (!cur.isAfter(spanEnd)) {
                    dateMetrics.getOrPut(cur) { mutableSetOf() }.add(metric)
                    cur = cur.plusDays(1)
                }
            }
        }

        dateMetrics.forEach { (date, metrics) ->
            enqueueMetricStatsRecompute(date, workManager, metrics)
        }
    }

    /** Writes [metric]'s override, then recomputes its stale-hash rows. See class doc. */
    suspend fun setOverrideAndInvalidate(metric: BaselineMetric, windowDays: Int, minimumDays: Int) {
        windowConfigRepository.setOverride(metric, windowDays, minimumDays)
        invalidateMetric(metric)
    }

    /** Clears [metric]'s override (reverting to fallback/global resolution), then recomputes its stale-hash rows. */
    suspend fun clearOverrideAndInvalidate(metric: BaselineMetric) {
        windowConfigRepository.clearOverride(metric)
        invalidateMetric(metric)
    }

    private suspend fun invalidateMetric(metric: BaselineMetric) {
        if (!calculators.containsKey(metric)) return
        val windowDays = windowConfigRepository.getEffectiveWindow(metric)
        val minimumDays = windowConfigRepository.getEffectiveMinimum(metric)
        val currentHash = MetricStatsConfigHash.compute(metric, windowDays, minimumDays, windowConfigRepository)

        val staleDates = metricDailyStatsDao.getAllForMetricOnce(metric)
            .filter { it.configHash != currentHash }
            .map { it.summaryDate }

        staleDates.forEach { date ->
            enqueueMetricStatsRecompute(date, workManager, setOf(metric))
        }
    }
}
