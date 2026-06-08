package com.athletedata.openAthleteMetrics.ble.sync

import android.util.Log
import com.athletedata.openAthleteMetrics.data.model.Activity
import com.athletedata.openAthleteMetrics.data.model.MetricReading
import com.athletedata.openAthleteMetrics.data.model.MetricType
import com.athletedata.openAthleteMetrics.data.model.SleepSession
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

private const val TAG = "SyncValidator"
private val EARLIEST_VALID_TIMESTAMP: Instant = Instant.parse("2020-01-01T00:00:00Z")

@Singleton
class SyncValidator @Inject constructor() {

    fun validateReadings(readings: List<MetricReading>): List<ValidationResult<MetricReading>> =
        readings.map { reading ->
            val reason = when {
                reading.value.isNaN() ->
                    "value is NaN for ${reading.metricType} at ${reading.recordedAt}"
                reading.value.isInfinite() ->
                    "value is Infinite for ${reading.metricType} at ${reading.recordedAt}"
                reading.recordedAt < EARLIEST_VALID_TIMESTAMP ->
                    "recordedAt ${reading.recordedAt} is before 2020-01-01"
                reading.recordedAt > Instant.now().plusSeconds(3_600) ->
                    "recordedAt ${reading.recordedAt} is more than 1 hour in the future"
                reading.unit.isBlank() ->
                    "unit is blank for ${reading.metricType} at ${reading.recordedAt}"
                !MetricType.entries.contains(reading.metricType) ->
                    "unknown MetricType: ${reading.metricType}"
                else -> null
            }
            if (reason != null) {
                Log.w(TAG, "Rejected MetricReading: $reason")
                ValidationResult.Rejected(reading, reason)
            } else {
                ValidationResult.Accepted(reading)
            }
        }

    fun validateSessions(sessions: List<SleepSession>): List<ValidationResult<SleepSession>> =
        sessions.map { session ->
            val actualMinutes = ChronoUnit.MINUTES.between(session.sleepStartMs, session.sleepEndMs).toInt()
            val reason = when {
                !session.sleepEndMs.isAfter(session.sleepStartMs) ->
                    "sleepEndMs ${session.sleepEndMs} is not after sleepStartMs ${session.sleepStartMs}"
                abs(actualMinutes - session.durationMinutes) > 2 ->
                    "durationMinutes ${session.durationMinutes} does not match computed $actualMinutes (tolerance 2 min)"
                session.date.isAfter(LocalDate.now()) ->
                    "date ${session.date} is in the future"
                else -> null
            }
            if (reason != null) {
                Log.w(TAG, "Rejected SleepSession: $reason")
                ValidationResult.Rejected(session, reason)
            } else {
                ValidationResult.Accepted(session)
            }
        }

    fun validateActivities(activities: List<Activity>): List<ValidationResult<Activity>> =
        activities.map { activity ->
            val reason = when {
                !activity.endTime.isAfter(activity.startTime) ->
                    "endTime ${activity.endTime} is not after startTime ${activity.startTime}"
                activity.durationMinutes <= 0 ->
                    "durationMinutes must be > 0, got ${activity.durationMinutes}"
                activity.deviceName.isBlank() ->
                    "deviceName is blank"
                else -> null
            }
            if (reason != null) {
                Log.w(TAG, "Rejected Activity: $reason")
                ValidationResult.Rejected(activity, reason)
            } else {
                ValidationResult.Accepted(activity)
            }
        }
}
