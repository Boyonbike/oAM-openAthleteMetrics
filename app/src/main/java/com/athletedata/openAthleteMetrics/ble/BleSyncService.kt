package com.athletedata.openAthleteMetrics.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.athletedata.openAthleteMetrics.R
import dagger.hilt.android.AndroidEntryPoint

/**
 * Keeps the process alive (and the BLE GATT connection with it) for the duration of an
 * active device connection/sync by running as a typed foreground service. Started and
 * stopped by AthleteDataApplication's BleConnectionState watcher — this service has no
 * sync logic of its own, it's purely a lifetime anchor + the mandatory FGS notification.
 */
@AndroidEntryPoint
class BleSyncService : Service() {

    companion object {
        private const val CHANNEL_ID = "ble_sync"
        private const val NOTIFICATION_ID = 4201
        const val EXTRA_DEVICE_NAME = "device_name"
        const val EXTRA_INDEX = "index"
        const val EXTRA_TOTAL = "total"
    }

    // Updated from Intent extras on each onStartCommand redelivery (see
    // AthleteDataApplication.observeBleSyncService) so a multi-device auto-sync run can show
    // per-device progress; falls back to a generic message for manual single-device syncs,
    // which never populate the extras.
    private var contentText: String = "Syncing device…"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val deviceName = intent?.getStringExtra(EXTRA_DEVICE_NAME)
        val index = intent?.getIntExtra(EXTRA_INDEX, -1) ?: -1
        val total = intent?.getIntExtra(EXTRA_TOTAL, -1) ?: -1
        if (deviceName != null && index > 0 && total > 0) {
            contentText = "Syncing $deviceName ($index of $total)…"
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
}
