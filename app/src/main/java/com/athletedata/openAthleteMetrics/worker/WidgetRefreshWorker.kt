package com.athletedata.openAthleteMetrics.worker

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.athletedata.openAthleteMetrics.glance.MetricGlanceWidget
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Periodic safety net for home-screen widget freshness: the event-triggered refresh
 * (see [com.athletedata.openAthleteMetrics.data.repository.RoomDailySummaryRepository]
 * and [com.athletedata.openAthleteMetrics.data.repository.RoomDailyContextRepository])
 * covers the common case, but this catches drift when the app hasn't synced recently.
 * The "last updated" timestamp on the widget itself is what keeps this honest to the
 * user rather than implying real-time data.
 */
@HiltWorker
class WidgetRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        MetricGlanceWidget().updateAll(applicationContext)
        return Result.success()
    }
}

/**
 * Enqueued unconditionally at app startup (mirrors [com.athletedata.openAthleteMetrics.worker.AppStartupWorker]'s
 * pattern) — a cheap no-op via [androidx.glance.appwidget.updateAll] when no widgets are placed.
 * 30 minutes satisfies "roughly every 30-60 minutes" with headroom above WorkManager's
 * hard 15-minute floor on periodic work.
 */
fun enqueuePeriodicWidgetRefresh(workManager: WorkManager) {
    val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(30, TimeUnit.MINUTES).build()
    workManager.enqueueUniquePeriodicWork(
        "widget_refresh",
        ExistingPeriodicWorkPolicy.KEEP,
        request,
    )
}
