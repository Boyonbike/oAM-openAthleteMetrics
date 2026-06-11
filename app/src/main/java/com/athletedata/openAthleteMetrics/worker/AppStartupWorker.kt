package com.athletedata.openAthleteMetrics.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.athletedata.openAthleteMetrics.data.repository.SyncSessionRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.time.Instant

@HiltWorker
class AppStartupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncSessionRepository: SyncSessionRepository,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = try {
        syncSessionRepository.markOldPartialsAsFailed(Instant.now().minusSeconds(3600))
        Result.success()
    } catch (e: Exception) {
        Timber.e(e, "AppStartupWorker failed")
        Result.failure()
    }

    companion object {
        fun enqueue(workManager: WorkManager) {
            workManager.enqueueUniqueWork(
                "app_startup",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<AppStartupWorker>().build(),
            )
        }
    }
}
