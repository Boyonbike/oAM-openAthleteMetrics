package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.model.BaselineMetric
import com.athletedata.openAthleteMetrics.data.model.BaselineRange
import kotlinx.coroutines.flow.Flow

/**
 * Contract for computed baseline ranges (mean ± 1 SD over a rolling window per metric).
 *
 * [recalculate] is called on-demand by DailySummaryWorker after it finishes writing a
 * day's summary — there is no periodic recalculation job.
 */
interface BaselineRepository {

    /** Live stream of the current baseline range for [metric], or null if fewer than 14 days of data exist yet. */
    fun observeRange(metric: BaselineMetric): Flow<BaselineRange?>

    /**
     * Recomputes and stores the baseline for [metric] from the last N days of daily_summary,
     * where N is [metric]'s effective window (see BaselineWindowConfigRepository: an explicit
     * per-metric override, else a built-in fallback default, else the global window setting).
     * Leaves any existing row untouched if fewer than [metric]'s effective minimum of
     * non-null values exist in the window.
     */
    suspend fun recalculate(metric: BaselineMetric)

    /** Stub — manual override storage is built in a later prompt; this fixes the interface shape now. */
    suspend fun setManualOverride(metric: BaselineMetric, lower: Double, upper: Double)

    /** Stub — see [setManualOverride]. */
    suspend fun clearManualOverride(metric: BaselineMetric)
}
