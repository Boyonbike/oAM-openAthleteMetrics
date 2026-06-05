package com.athletedata.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point.
 *
 * Implements [Configuration.Provider] so WorkManager uses [HiltWorkerFactory]
 * instead of the default factory. This is the mechanism that allows
 * [DailySummaryWorker] (and any future workers) to receive injected
 * dependencies via @HiltWorker + @AssistedInject.
 *
 * The default WorkManager auto-initializer is removed in AndroidManifest.xml;
 * WorkManager initialises lazily on first use with this configuration instead.
 */
@HiltAndroidApp
class AthleteDataApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
