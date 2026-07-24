package com.athletedata.openAthleteMetrics.di

import com.athletedata.openAthleteMetrics.data.model.BaselineMetric
import dagger.MapKey

/** Dagger map key for the `Map<BaselineMetric, MetricStatsCalculator>` multibinding in [CalculatorModule]. */
@MapKey
annotation class BaselineMetricKey(val value: BaselineMetric)
