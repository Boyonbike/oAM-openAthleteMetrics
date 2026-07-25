package com.athletedata.openAthleteMetrics

import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.athletedata.openAthleteMetrics.ble.BleConnectionState
import com.athletedata.openAthleteMetrics.ble.BleEngine
import com.athletedata.openAthleteMetrics.ble.BleSyncService
import com.athletedata.openAthleteMetrics.ble.driver.DriverRegistry
import com.athletedata.openAthleteMetrics.ble.sync.DeviceSyncProcessor
import com.athletedata.openAthleteMetrics.ble.sync.MultiDeviceSyncOrchestrator
import com.athletedata.openAthleteMetrics.ble.sync.SyncProgress
import com.athletedata.openAthleteMetrics.ble.wasm.WasmRuntimeCheck
import com.athletedata.openAthleteMetrics.data.db.AppDatabase
import com.athletedata.openAthleteMetrics.data.db.QuestionDefinitionDao
import com.athletedata.openAthleteMetrics.data.db.QuestionDefinitionEntity
import com.athletedata.openAthleteMetrics.worker.AppStartupWorker
import com.athletedata.openAthleteMetrics.worker.enqueuePeriodicWidgetRefresh
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class AthleteDataApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var bleEngine: BleEngine

    @Inject
    lateinit var multiDeviceSyncOrchestrator: MultiDeviceSyncOrchestrator

    @Inject
    lateinit var syncProcessor: DeviceSyncProcessor

    @Inject
    lateinit var driverRegistry: DriverRegistry

    @Inject
    lateinit var questionDefinitionDao: QuestionDefinitionDao

    private val applicationScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
            Timber.e(throwable, "Unhandled exception in applicationScope")
        }
    )

    // Whether BleSyncService is (or was just asked to be) running, tracked so we only call
    // start/stop on real edge transitions rather than on every connectionState emission
    // (e.g. Connected re-emits on every packet received).
    private var syncServiceActive = false
    private var pendingStopJob: Job? = null

    // Tracks what BleSyncService was last told to show, decoupled from syncServiceActive's
    // rising-edge check, so per-device progress updates keep landing throughout a multi-device
    // auto-sync run (syncServiceActive stays true for the whole run once started).
    private var lastNotifiedProgress: SyncProgress? = null

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        if (WasmRuntimeCheck.verify()) {
            Timber.d("WASM runtime: OK")
        } else {
            Timber.e("WASM runtime: FAILED TO INITIALISE")
        }
        observeBleSyncService()
        applicationScope.launch { driverRegistry.initialiseDrivers() }
        applicationScope.launch(Dispatchers.IO) {
            if (questionDefinitionDao.countLifestyle() == 0) {
                questionDefinitionDao.insertAll(
                    AppDatabase.LIFESTYLE_SEEDS.map { seed ->
                        QuestionDefinitionEntity(
                            name = seed.name,
                            type = seed.type,
                            category = "LIFESTYLE",
                            sortOrder = seed.sortOrder,
                            isStarred = seed.isStarred,
                        )
                    }
                )
            }
        }
        AppStartupWorker.enqueue(WorkManager.getInstance(this))
        enqueuePeriodicWidgetRefresh(WorkManager.getInstance(this))
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    // Best-effort only: Application.onTerminate() is documented to never fire on real
    // devices (emulator/test-only). An ON_STOP-based alternative was considered and
    // rejected: BleSyncService keeps bleEngine/syncProcessor alive while the app is
    // backgrounded during an active sync (see observeBleSyncService() below), so
    // shutting them down on backgrounding would break in-flight syncs.
    override fun onTerminate() {
        bleEngine.shutdown()
        syncProcessor.shutdown()
        super.onTerminate()
    }

    // Starts/stops BleSyncService (a foreground service that keeps this process alive) in
    // step with BleEngine's connection state, so an in-flight sync survives the app being
    // backgrounded. Runs for the whole connected span (Connecting through SyncComplete),
    // not just the narrow Syncing state, since sync commands are written and data starts
    // streaming immediately after connect — well before Syncing is ever set.
    private fun observeBleSyncService() {
        applicationScope.launch {
            combine(bleEngine.connectionState, multiDeviceSyncOrchestrator.progress) { state, progress ->
                state to progress
            }.collect { (state, progress) ->
                val shouldBeActive = when (state) {
                    is BleConnectionState.Connecting,
                    is BleConnectionState.Connected,
                    is BleConnectionState.Syncing,
                    is BleConnectionState.Parsing,
                    is BleConnectionState.SyncComplete -> true
                    else -> false
                }
                if (shouldBeActive) {
                    pendingStopJob?.cancel()
                    pendingStopJob = null
                    // Re-deliver the intent whenever progress changes, even if the service is
                    // already running, so per-device notification text stays current —
                    // startForegroundService() on an already-running FGS just redelivers
                    // onStartCommand with the new extras.
                    if (!syncServiceActive || progress != lastNotifiedProgress) {
                        startBleSyncService(progress)
                        lastNotifiedProgress = progress
                    }
                } else if (syncServiceActive && pendingStopJob == null) {
                    // Debounce the stop: a brief Disconnected -> Connecting blip during
                    // BleEngine's own GATT-reconnect retry (2s/4s/8s backoff) shouldn't tear
                    // down and immediately recreate the service.
                    pendingStopJob = launch {
                        delay(SYNC_SERVICE_STOP_DEBOUNCE_MS)
                        stopBleSyncService()
                        pendingStopJob = null
                    }
                }
            }
        }
    }

    private fun startBleSyncService(progress: SyncProgress? = null) {
        val intent = Intent(this, BleSyncService::class.java).apply {
            if (progress != null) {
                putExtra(BleSyncService.EXTRA_DEVICE_NAME, progress.deviceName)
                putExtra(BleSyncService.EXTRA_INDEX, progress.index)
                putExtra(BleSyncService.EXTRA_TOTAL, progress.total)
            }
        }
        try {
            ContextCompat.startForegroundService(this, intent)
            syncServiceActive = true
        } catch (e: ForegroundServiceStartNotAllowedException) {
            // Every connect() call site today is UI-triggered, so the app should always be
            // in an exempt state the first time this fires. Log rather than crash in case a
            // future call site or OEM process-restore path ever calls connect() from a truly
            // backgrounded cold start.
            Timber.e(e, "BleSyncService: foreground service start not allowed")
        }
    }

    private fun stopBleSyncService() {
        stopService(Intent(this, BleSyncService::class.java))
        syncServiceActive = false
        lastNotifiedProgress = null
    }

    companion object {
        private const val SYNC_SERVICE_STOP_DEBOUNCE_MS = 5_000L
    }
}
