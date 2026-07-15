package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.BaselineDao
import com.athletedata.openAthleteMetrics.data.db.BaselineEntity
import com.athletedata.openAthleteMetrics.data.model.BaselineMetric
import com.athletedata.openAthleteMetrics.data.model.BaselineRange
import com.athletedata.openAthleteMetrics.data.model.BaselineSource
import com.athletedata.openAthleteMetrics.data.model.DailySummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

@Singleton
class RoomBaselineRepository @Inject constructor(
    private val dao: BaselineDao,
    private val dailySummaryRepository: DailySummaryRepository,
    private val windowConfigRepository: BaselineWindowConfigRepository,
) : BaselineRepository {

    override fun observeRange(metric: BaselineMetric): Flow<BaselineRange?> =
        dao.observe(metric).map { entity ->
            entity?.let { BaselineRange(lower = it.lower, upper = it.upper, source = BaselineSource.AUTO) }
        }

    override suspend fun recalculate(metric: BaselineMetric) {
        try {
            val windowDays = windowConfigRepository.getEffectiveWindow(metric)
            val minDays = windowConfigRepository.getEffectiveMinimum(metric)
            val to = LocalDate.now()
            val from = to.minusDays((windowDays - 1).toLong())
            val summaries = dailySummaryRepository.getSummariesForRange(from, to).first()
            val values = summaries.mapNotNull { valueFor(metric, it) }

            if (values.size < minDays) {
                Timber.d(
                    "BaselineRepository: only %d/%d days of %s data in window — leaving existing baseline in place",
                    values.size, minDays, metric,
                )
                return
            }

            val mean = values.average()
            val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
            val stdDev = sqrt(variance)

            dao.upsert(
                BaselineEntity(
                    metricType = metric,
                    mean = mean,
                    stdDev = stdDev,
                    lower = mean - stdDev,
                    upper = mean + stdDev,
                    windowDays = windowDays,
                    calculatedAt = Instant.now().toEpochMilli(),
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to recalculate baseline for $metric")
            throw e
        }
    }

    override suspend fun setManualOverride(metric: BaselineMetric, lower: Double, upper: Double): Unit =
        throw NotImplementedError("Manual baseline override is not implemented yet")

    override suspend fun clearManualOverride(metric: BaselineMetric): Unit =
        throw NotImplementedError("Manual baseline override is not implemented yet")

    /** Maps a metric to its daily_summary column. HRV uses the overnight-window HRV value. */
    private fun valueFor(metric: BaselineMetric, summary: DailySummary): Double? = when (metric) {
        BaselineMetric.HR    -> summary.avgHrBpm
        BaselineMetric.HRV   -> summary.overnightHrvMs
        BaselineMetric.RHR   -> summary.restingHrBpm
        BaselineMetric.SLEEP -> summary.sleepMinutes?.toDouble()
        BaselineMetric.SPO2  -> summary.avgSpo2Pct
        BaselineMetric.STEPS -> summary.steps?.toDouble()
        // Sleep-detail metrics are plain-average display values computed by
        // SleepAverageCalculator, not part of the mean±SD baseline-band system — no band
        // should ever exist for these, so recalculate() always no-ops (insufficient-data path).
        BaselineMetric.SLEEP_DEEP,
        BaselineMetric.SLEEP_LIGHT,
        BaselineMetric.SLEEP_REM,
        BaselineMetric.SLEEP_AWAKE,
        BaselineMetric.SLEEP_ONSET,
        BaselineMetric.SLEEP_WAKE -> null
    }
}
