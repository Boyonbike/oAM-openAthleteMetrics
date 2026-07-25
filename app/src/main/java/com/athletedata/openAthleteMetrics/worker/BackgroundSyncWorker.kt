package com.athletedata.openAthleteMetrics.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.athletedata.openAthleteMetrics.R
import com.athletedata.openAthleteMetrics.ble.sync.MultiDeviceSyncOrchestrator
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import timber.log.Timber

/**
 * Unattended background counterpart to MainActivity's startup auto-sync: fires on a ~6h
 * cadence so devices sync even if the app is never opened. Drives the same
 * MultiDeviceSyncOrchestrator.runIfIdle() gate MainActivity and CompanionPresenceSyncTrigger
 * use, so a run already in progress from either of those simply causes this to no-op rather
 * than double-connecting.
 *
 * setForeground() at the top of doWork() is a brief self-promotion, not the sync's actual
 * lifetime anchor - AthleteDataApplication.observeBleSyncService() takes over BleSyncService
 * for the run's actual duration as soon as bleEngine.connectionState moves. Its only job is
 * to bridge the gap: a plain background Worker execution does not itself exempt the process
 * from Android 12+ background-foreground-service-start restrictions, but a Worker calling
 * setForeground() on itself is specifically allowed to, which also takes the whole process
 * out of "background" state for the moment after - unblocking the subsequent BleSyncService
 * start from AthleteDataApplication.
 */
@HiltWorker
class BackgroundSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val multiDeviceSyncOrchestrator: MultiDeviceSyncOrchestrator,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = try {
        setForeground(createForegroundInfo())
        val ran = multiDeviceSyncOrchestrator.runIfIdle()
        if (ran) {
            Timber.i("BackgroundSyncWorker: auto-sync run completed")
        } else {
            Timber.i("BackgroundSyncWorker: another sync was already in progress, skipped this run")
        }
        Result.success()
    } catch (e: Exception) {
        Timber.e(e, "BackgroundSyncWorker failed")
        Result.retry()
    }

    private fun createForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText("Checking for device sync…")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            applicationContext.getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        // Same channel id BleSyncService uses - one notification channel for all sync
        // activity in Settings, not a second "background sync" channel.
        private const val CHANNEL_ID = "ble_sync"
        private const val NOTIFICATION_ID = 4202
        const val WORK_NAME = "background_ble_sync"

        fun enqueue(workManager: WorkManager) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<BackgroundSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
