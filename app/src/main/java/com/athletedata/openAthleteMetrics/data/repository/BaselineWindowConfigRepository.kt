package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.BaselineWindowConfigEntity
import com.athletedata.openAthleteMetrics.data.model.BaselineMetric
import kotlinx.coroutines.flow.Flow

/**
 * Contract for per-[BaselineMetric] overrides of the baseline rolling window and
 * minimum-days-of-data requirement, consumed by [BaselineRepository]'s auto-calculation.
 *
 * [getEffectiveWindow] and [getEffectiveMinimum] resolve independently per field:
 *   1. The non-null column on the metric's override row in `baseline_window_config`, if any.
 *   2. Else this metric's entry in [com.athletedata.openAthleteMetrics.data.model.BaselineFallbackDefaults], if present.
 *   3. Else the global default — [SettingsRepository.getBaselineWindowDays] for window,
 *      a fixed 14-day minimum for minimum.
 */
interface BaselineWindowConfigRepository {

    /** Resolves the effective rolling-window length (days) for [metric]. See class doc for order. */
    suspend fun getEffectiveWindow(metric: BaselineMetric): Int

    /** Resolves the effective minimum-days-of-data requirement for [metric]. See class doc for order. */
    suspend fun getEffectiveMinimum(metric: BaselineMetric): Int

    /** Writes an explicit override row for [metric], replacing any existing row. */
    suspend fun setOverride(metric: BaselineMetric, windowDays: Int, minimumDays: Int)

    /** Deletes [metric]'s override row, if any, reverting it to fallback/global resolution. */
    suspend fun clearOverride(metric: BaselineMetric)

    /** Live stream of [metric]'s raw override row, or null if no override is active. */
    fun observeOverride(metric: BaselineMetric): Flow<BaselineWindowConfigEntity?>
}
