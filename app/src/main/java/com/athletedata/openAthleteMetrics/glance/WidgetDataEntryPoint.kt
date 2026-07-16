package com.athletedata.openAthleteMetrics.glance

import com.athletedata.openAthleteMetrics.data.repository.DailyContextRepository
import com.athletedata.openAthleteMetrics.data.repository.DailySummaryRepository
import com.athletedata.openAthleteMetrics.data.repository.SettingsRepository
import com.athletedata.openAthleteMetrics.data.repository.SleepDetailProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * [MetricGlanceWidget] is instantiated directly by a [androidx.glance.appwidget.GlanceAppWidgetReceiver],
 * not by Hilt, so it has no constructor-injection path to the app's repositories. This
 * entry point is the sanctioned way to reach them from [MetricGlanceWidget.provideGlance]
 * via [dagger.hilt.android.EntryPointAccessors.fromApplication].
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetDataEntryPoint {
    fun dailySummaryRepository(): DailySummaryRepository
    fun dailyContextRepository(): DailyContextRepository
    fun settingsRepository(): SettingsRepository
    fun sleepDetailProvider(): SleepDetailProvider
}
