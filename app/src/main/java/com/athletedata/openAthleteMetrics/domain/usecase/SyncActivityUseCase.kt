package com.athletedata.openAthleteMetrics.domain.usecase

import com.athletedata.openAthleteMetrics.data.model.Activity
import com.athletedata.openAthleteMetrics.data.repository.ActivityRepository
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class SyncActivityUseCase @Inject constructor(
    private val activityRepository: ActivityRepository,
) {
    suspend fun execute(activities: List<Activity>): Int =
        activityRepository.insertAllFromDevice(activities.map { fixDurationIfNeeded(it) })

    private fun fixDurationIfNeeded(activity: Activity): Activity {
        val derivedMinutes = ((activity.endTime.toEpochMilli() - activity.startTime.toEpochMilli()) / 60_000L).toInt()
        return if (abs(derivedMinutes - activity.durationMinutes) > 2) {
            Timber.w(
                "Activity duration mismatch: stored=%d derived=%d — using derived value",
                activity.durationMinutes, derivedMinutes,
            )
            activity.copy(durationMinutes = derivedMinutes)
        } else {
            activity
        }
    }
}
