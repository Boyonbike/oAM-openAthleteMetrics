package com.athletedata.openAthleteMetrics

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.athletedata.openAthleteMetrics.ble.BleEngine
import com.athletedata.openAthleteMetrics.ble.driver.DriverRegistry
import com.athletedata.openAthleteMetrics.ble.sync.DeviceSyncProcessor
import com.athletedata.openAthleteMetrics.ble.wasm.WasmRuntimeCheck
import com.athletedata.openAthleteMetrics.data.db.AppDatabase
import com.athletedata.openAthleteMetrics.data.db.QuestionDefinitionDao
import com.athletedata.openAthleteMetrics.data.db.QuestionDefinitionEntity
import com.athletedata.openAthleteMetrics.worker.AppStartupWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    lateinit var syncProcessor: DeviceSyncProcessor

    @Inject
    lateinit var driverRegistry: DriverRegistry

    @Inject
    lateinit var questionDefinitionDao: QuestionDefinitionDao

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onTerminate() {
        bleEngine.shutdown()
        syncProcessor.shutdown()
        super.onTerminate()
    }
}
