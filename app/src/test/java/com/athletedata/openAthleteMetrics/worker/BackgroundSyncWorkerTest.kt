package com.athletedata.openAthleteMetrics.worker

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.athletedata.openAthleteMetrics.ble.sync.MultiDeviceSyncOrchestrator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BackgroundSyncWorkerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun `enqueue registers a periodic request with the 6h cadence and battery-not-low constraint`() {
        val config = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        val workManager = WorkManager.getInstance(context)

        BackgroundSyncWorker.enqueue(workManager)

        val workInfo = workManager.getWorkInfosForUniqueWork(BackgroundSyncWorker.WORK_NAME).get().single()
        assertTrue(workInfo.constraints.requiresBatteryNotLow())
        assertEquals(TimeUnit.HOURS.toMillis(6), workInfo.periodicityInfo?.repeatIntervalMillis)
    }

    @Test
    fun `doWork defers without touching the engine when the orchestrator reports busy`() {
        val fakeOrchestrator = mockk<MultiDeviceSyncOrchestrator>(relaxed = true) {
            coEvery { runIfIdle() } returns false
        }
        val worker = TestListenableWorkerBuilder<BackgroundSyncWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = BackgroundSyncWorker(appContext, workerParameters, fakeOrchestrator)
            })
            .build()

        val result = worker.startWork().get()

        assertTrue(result is ListenableWorker.Result.Success)
        coVerify(exactly = 1) { fakeOrchestrator.runIfIdle() }
    }
}
