package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.MetricDailyStatsDao
import com.athletedata.openAthleteMetrics.data.db.MetricDailyStatsEntity
import com.athletedata.openAthleteMetrics.data.model.BaselineMetric
import com.athletedata.openAthleteMetrics.data.model.BaselineRange
import com.athletedata.openAthleteMetrics.data.model.BaselineSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-side counterpart to [MetricDailyStatsWriter]: point-in-time reads of `metric_daily_stats`
 * for UI consumers, keyed on the viewed date rather than "current" (see [BaselineRepository],
 * which has no date column and can only ever answer for "now").
 *
 * A stored row whose [MetricDailyStatsEntity.configHash] no longer matches the metric's
 * currently-resolved window/minimum is treated as stale and filtered to null — [MetricStatsBackfillCoordinator]
 * is responsible for proactively refreshing rows after a config change; this reader only avoids
 * surfacing a value computed under a since-changed config.
 */
@Singleton
class MetricDailyStatsReader @Inject constructor(
    private val dao: MetricDailyStatsDao,
    private val windowConfigRepo: BaselineWindowConfigRepository,
) {

    /** Null if no row exists for (metric, date), or if the row's config_hash is stale. */
    fun observeFresh(metric: BaselineMetric, date: LocalDate): Flow<MetricDailyStatsEntity?> =
        dao.observe(metric, date).map { entity -> entity?.takeIf { isCurrent(it) } }

    private suspend fun isCurrent(entity: MetricDailyStatsEntity): Boolean {
        val windowDays = windowConfigRepo.getEffectiveWindow(entity.metricType)
        val minimumDays = windowConfigRepo.getEffectiveMinimum(entity.metricType)
        val currentHash = MetricStatsConfigHash.compute(entity.metricType, windowDays, minimumDays, windowConfigRepo)
        return currentHash == entity.configHash
    }

    /** [entity.mean]±[entity.stdDev] mapped to the existing [BaselineRange] band shape; null if either is null. */
    fun observeBaselineRange(metric: BaselineMetric, date: LocalDate): Flow<BaselineRange?> =
        observeFresh(metric, date).map { entity ->
            val mean = entity?.mean ?: return@map null
            val stdDev = entity.stdDev ?: return@map null
            BaselineRange(lower = mean - stdDev, upper = mean + stdDev, source = BaselineSource.AUTO)
        }
}
