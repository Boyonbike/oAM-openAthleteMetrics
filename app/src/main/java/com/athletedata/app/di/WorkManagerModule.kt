package com.athletedata.app.di

import android.content.Context
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides [WorkManager] as a singleton so it can be injected into repositories
 * and other classes that need to enqueue workers.
 *
 * [WorkManager.getInstance] is safe to call here because by the time any Hilt
 * injection happens, [AthleteDataApplication.onCreate] has already run and the
 * custom [Configuration] (with [HiltWorkerFactory]) is in place via the
 * [Configuration.Provider] interface.
 */
@Module
@InstallIn(SingletonComponent::class)
object WorkManagerModule {

    /**
     * Provides the app-scoped [WorkManager] instance.
     * Injected into [RoomMetricRepository] and [RoomSleepRepository] so they
     * can call [enqueueSummaryWorker] after writes.
     */
    @Provides
    @Singleton
    fun provideWorkManager(
        @ApplicationContext context: Context,
    ): WorkManager = WorkManager.getInstance(context)
}
