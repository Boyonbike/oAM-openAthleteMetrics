package com.athletedata.openAthleteMetrics.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.athletedata.openAthleteMetrics.data.model.BaselineMetric
import com.athletedata.openAthleteMetrics.data.repository.MetricDailyStatsWriter
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Recomputes `metric_daily_stats` rows for a single date, independent of
 * [DailySummaryWorker] — used for backfill-span invalidation (a change to an earlier date
 * widening a later date's trailing window) and config-change invalidation (a window/minimum
 * override changing a metric's resolution), neither of which needs `daily_summary` itself
 * re-aggregated from raw readings.
 *
 * [KEY_METRICS] is optional: absent means "every in-scope metric" (the backfill-span case,
 * where a change to source data affects all metrics derived from that day's summary);
 * present means recompute only that subset (the config-change case, where exactly one
 * metric's resolution changed and the others must stay untouched).
 */
@HiltWorker
class MetricStatsRecomputeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val writer: MetricDailyStatsWriter,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val dateStr = inputData.getString(KEY_DATE) ?: return Result.failure()
        val date = try {
            LocalDate.parse(dateStr)
        } catch (e: DateTimeParseException) {
            Timber.tag(TAG).e(e, "malformed date=%s — failing permanently", dateStr)
            return Result.failure()
        }
        val metrics = inputData.getString(KEY_METRICS)
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?.map { BaselineMetric.valueOf(it) }

        return try {
            val zone = ZoneId.systemDefault()
            if (metrics != null) writer.write(date, zone, metrics) else writer.write(date, zone)
            Result.success()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "MetricStatsRecomputeWorker failed for date=%s", dateStr)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "MetricStatsRecomputeWorker"
        const val KEY_DATE = "date"
        const val KEY_METRICS = "metrics"
        /** Applied to every enqueued request so callers/tests can enumerate in-flight work. */
        const val WORK_TAG = "metric_stats_recompute"
    }
}

/**
 * Enqueues a [MetricStatsRecomputeWorker] for [date], recomputing [metrics] (or every in-scope
 * metric when null). Unique work name includes the metric subset, not just the date — a
 * date-only key under REPLACE could let a later single-metric enqueue silently cancel an
 * earlier, different single-metric enqueue for the same date.
 */
fun enqueueMetricStatsRecompute(
    date: LocalDate,
    workManager: WorkManager,
    metrics: Set<BaselineMetric>? = null,
) {
    val subsetKey = metrics?.map { it.name }?.sorted()?.joinToString("-") ?: "all"
    val request = OneTimeWorkRequestBuilder<MetricStatsRecomputeWorker>()
        .setInputData(workDataOf(
            MetricStatsRecomputeWorker.KEY_DATE to date.toString(),
            MetricStatsRecomputeWorker.KEY_METRICS to metrics?.joinToString(",") { it.name },
        ))
        .addTag(MetricStatsRecomputeWorker.WORK_TAG)
        .build()
    workManager.enqueueUniqueWork(
        "metric_stats_${date}_$subsetKey",
        ExistingWorkPolicy.REPLACE,
        request,
    )
}
