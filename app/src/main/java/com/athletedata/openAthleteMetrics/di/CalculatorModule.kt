package com.athletedata.openAthleteMetrics.di

import com.athletedata.openAthleteMetrics.data.model.BaselineMetric
import com.athletedata.openAthleteMetrics.data.repository.BaselineWindowConfigRepository
import com.athletedata.openAthleteMetrics.data.repository.DailySummaryRepository
import com.athletedata.openAthleteMetrics.data.repository.GenericTrailingStatsCalculator
import com.athletedata.openAthleteMetrics.data.repository.MetricStatsCalculator
import com.athletedata.openAthleteMetrics.data.repository.SleepStageStatsCalculator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap

/**
 * Multibinds a `Map<BaselineMetric, MetricStatsCalculator>` (one entry per metric in scope for
 * `metric_daily_stats`), so a consumer can resolve `calculators[metric]` without a `when`
 * dispatcher, and a future metric-specific rework only touches that metric's `@Provides` method.
 *
 * SLEEP_ONSET/SLEEP_WAKE have no entry — they're out of scope for this table (circular
 * time-of-day means, not a numeric mean/std-dev).
 *
 * First `@IntoMap`/`@MapKey` usage in this codebase. Note for consumers: because Kotlin's `Map`
 * is declared `out V`, injecting this map requires `Map<BaselineMetric, @JvmSuppressWildcards MetricStatsCalculator>`
 * in the constructor signature, or the binding won't match.
 */
@Module
@InstallIn(SingletonComponent::class)
object CalculatorModule {

    @Provides
    @IntoMap
    @BaselineMetricKey(BaselineMetric.HR)
    fun provideHrCalculator(
        summaryRepo: DailySummaryRepository,
        windowConfigRepo: BaselineWindowConfigRepository,
    ): MetricStatsCalculator =
        GenericTrailingStatsCalculator(BaselineMetric.HR, summaryRepo, windowConfigRepo) { it.avgHrBpm }

    @Provides
    @IntoMap
    @BaselineMetricKey(BaselineMetric.HRV)
    fun provideHrvCalculator(
        summaryRepo: DailySummaryRepository,
        windowConfigRepo: BaselineWindowConfigRepository,
    ): MetricStatsCalculator =
        GenericTrailingStatsCalculator(BaselineMetric.HRV, summaryRepo, windowConfigRepo) { it.overnightHrvMs }

    @Provides
    @IntoMap
    @BaselineMetricKey(BaselineMetric.RHR)
    fun provideRhrCalculator(
        summaryRepo: DailySummaryRepository,
        windowConfigRepo: BaselineWindowConfigRepository,
    ): MetricStatsCalculator =
        GenericTrailingStatsCalculator(BaselineMetric.RHR, summaryRepo, windowConfigRepo) { it.restingHrBpm }

    @Provides
    @IntoMap
    @BaselineMetricKey(BaselineMetric.SLEEP)
    fun provideSleepCalculator(
        summaryRepo: DailySummaryRepository,
        windowConfigRepo: BaselineWindowConfigRepository,
    ): MetricStatsCalculator =
        GenericTrailingStatsCalculator(BaselineMetric.SLEEP, summaryRepo, windowConfigRepo) { it.sleepMinutes?.toDouble() }

    @Provides
    @IntoMap
    @BaselineMetricKey(BaselineMetric.SPO2)
    fun provideSpo2Calculator(
        summaryRepo: DailySummaryRepository,
        windowConfigRepo: BaselineWindowConfigRepository,
    ): MetricStatsCalculator =
        GenericTrailingStatsCalculator(BaselineMetric.SPO2, summaryRepo, windowConfigRepo) { it.avgSpo2Pct }

    @Provides
    @IntoMap
    @BaselineMetricKey(BaselineMetric.STEPS)
    fun provideStepsCalculator(
        summaryRepo: DailySummaryRepository,
        windowConfigRepo: BaselineWindowConfigRepository,
    ): MetricStatsCalculator =
        GenericTrailingStatsCalculator(BaselineMetric.STEPS, summaryRepo, windowConfigRepo) { it.steps?.toDouble() }

    @Provides
    @IntoMap
    @BaselineMetricKey(BaselineMetric.SLEEP_DEEP)
    fun provideSleepDeepCalculator(
        summaryRepo: DailySummaryRepository,
        windowConfigRepo: BaselineWindowConfigRepository,
    ): MetricStatsCalculator =
        SleepStageStatsCalculator(BaselineMetric.SLEEP_DEEP, summaryRepo, windowConfigRepo) { it.sleepDeepMinutes }

    @Provides
    @IntoMap
    @BaselineMetricKey(BaselineMetric.SLEEP_LIGHT)
    fun provideSleepLightCalculator(
        summaryRepo: DailySummaryRepository,
        windowConfigRepo: BaselineWindowConfigRepository,
    ): MetricStatsCalculator =
        SleepStageStatsCalculator(BaselineMetric.SLEEP_LIGHT, summaryRepo, windowConfigRepo) { it.sleepLightMinutes }

    @Provides
    @IntoMap
    @BaselineMetricKey(BaselineMetric.SLEEP_REM)
    fun provideSleepRemCalculator(
        summaryRepo: DailySummaryRepository,
        windowConfigRepo: BaselineWindowConfigRepository,
    ): MetricStatsCalculator =
        SleepStageStatsCalculator(BaselineMetric.SLEEP_REM, summaryRepo, windowConfigRepo) { it.sleepRemMinutes }

    @Provides
    @IntoMap
    @BaselineMetricKey(BaselineMetric.SLEEP_AWAKE)
    fun provideSleepAwakeCalculator(
        summaryRepo: DailySummaryRepository,
        windowConfigRepo: BaselineWindowConfigRepository,
    ): MetricStatsCalculator =
        SleepStageStatsCalculator(BaselineMetric.SLEEP_AWAKE, summaryRepo, windowConfigRepo) { it.sleepAwakeMinutes }
}
