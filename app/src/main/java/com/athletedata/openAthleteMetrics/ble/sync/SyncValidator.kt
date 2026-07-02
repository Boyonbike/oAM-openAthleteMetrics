package com.athletedata.openAthleteMetrics.ble.sync

import com.athletedata.openAthleteMetrics.data.model.Activity
import com.athletedata.openAthleteMetrics.data.model.MetricReading
import com.athletedata.openAthleteMetrics.data.model.MetricType
import com.athletedata.openAthleteMetrics.data.model.SleepSession
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import timber.log.Timber

private val EARLIEST_VALID_TIMESTAMP: Instant = Instant.parse("2020-01-01T00:00:00Z")
private val UTC_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC) // VALIDATOR-LOG
private val THRESHOLD_EPOCH_MS: Long = EARLIEST_VALID_TIMESTAMP.toEpochMilli() // VALIDATOR-LOG
private val THRESHOLD_UTC_STRING: String = UTC_FORMATTER.format(EARLIEST_VALID_TIMESTAMP) // VALIDATOR-LOG
private const val TAG = "data-pathway-tracker" // VALIDATOR-LOG

@Singleton
class SyncValidator @Inject constructor() {

    fun validateReadings(readings: List<MetricReading>): List<ValidationResult<MetricReading>> {
        var accepted = 0
        var rejected = 0
        val results = readings.map { reading ->
            val ts = reading.recordedAt
            val tsMs = ts.toEpochMilli()
            val result: ValidationResult<MetricReading> = when {
                reading.value.isNaN() -> {
                    Timber.tag(TAG).e( // VALIDATOR-LOG
                        "[STAGE-3 VALIDATOR] DROP metric=%s reason=nan-value value=NaN recordedAt=%d",
                        reading.metricType, tsMs
                    )
                    ValidationResult.Rejected(reading, "value is NaN for ${reading.metricType} at $ts")
                }
                reading.value.isInfinite() -> {
                    Timber.tag(TAG).e( // VALIDATOR-LOG
                        "[STAGE-3 VALIDATOR] DROP metric=%s reason=infinite-value value=%s recordedAt=%d",
                        reading.metricType, reading.value, tsMs
                    )
                    ValidationResult.Rejected(reading, "value is Infinite for ${reading.metricType} at $ts")
                }
                ts < EARLIEST_VALID_TIMESTAMP -> {
                    Timber.tag(TAG).e( // VALIDATOR-LOG
                        "[STAGE-3 VALIDATOR] DROP metric=%s reason=timestamp-before-threshold\n  recordedAt=%d (%s) threshold=%d (%s)",
                        reading.metricType, tsMs, UTC_FORMATTER.format(ts),
                        THRESHOLD_EPOCH_MS, THRESHOLD_UTC_STRING
                    )
                    ValidationResult.Rejected(reading, "recordedAt $ts is before 2020-01-01")
                }
                ts > Instant.now().plusSeconds(3_600) -> {
                    Timber.tag(TAG).e( // VALIDATOR-LOG
                        "[STAGE-3 VALIDATOR] DROP metric=%s reason=timestamp-future recordedAt=%d (%s)",
                        reading.metricType, tsMs, UTC_FORMATTER.format(ts)
                    )
                    ValidationResult.Rejected(reading, "recordedAt $ts is more than 1 hour in the future")
                }
                reading.unit.isBlank() -> {
                    Timber.tag(TAG).e( // VALIDATOR-LOG
                        "[STAGE-3 VALIDATOR] DROP metric=%s reason=blank-unit recordedAt=%d",
                        reading.metricType, tsMs
                    )
                    ValidationResult.Rejected(reading, "unit is blank for ${reading.metricType} at $ts")
                }
                !MetricType.entries.contains(reading.metricType) -> {
                    Timber.tag(TAG).e( // VALIDATOR-LOG
                        "[STAGE-3 VALIDATOR] DROP metric=%s reason=unknown-metric-type recordedAt=%d",
                        reading.metricType, tsMs
                    )
                    ValidationResult.Rejected(reading, "unknown MetricType: ${reading.metricType}")
                }
                else -> ValidationResult.Accepted(reading)
            }
            if (result is ValidationResult.Accepted) accepted++ else rejected++
            result
        }
        if (rejected > 0) { // VALIDATOR-LOG
            Timber.tag(TAG).e("[STAGE-3 VALIDATOR] complete total=%d accepted=%d rejected=%d", readings.size, accepted, rejected)
        } else {
            Timber.tag(TAG).d("[STAGE-3 VALIDATOR] complete total=%d accepted=%d rejected=%d", readings.size, accepted, rejected)
        }
        return results
    }

    fun validateSessions(sessions: List<SleepSession>): List<ValidationResult<SleepSession>> {
        var accepted = 0
        var rejected = 0
        val results = sessions.map { session ->
            val dateIso = session.date.toString()
            val startMs = session.sleepStartMs.toEpochMilli()
            val endMs = session.sleepEndMs.toEpochMilli()
            val durationMs = endMs - startMs
            val actualMinutes = (durationMs / 60_000L).toInt()
            val result: ValidationResult<SleepSession> = when {
                session.sleepStartMs.isBefore(EARLIEST_VALID_TIMESTAMP) -> {
                    Timber.tag(TAG).e( // VALIDATOR-LOG
                        "[STAGE-3 VALIDATOR] DROP session=%s reason=start-before-threshold recordedAt=%d (%s) threshold=%d (%s)",
                        dateIso, startMs, UTC_FORMATTER.format(session.sleepStartMs),
                        THRESHOLD_EPOCH_MS, THRESHOLD_UTC_STRING
                    )
                    ValidationResult.Rejected(session, "sleepStartMs ${session.sleepStartMs} is before 2020-01-01")
                }
                session.sleepEndMs.isBefore(EARLIEST_VALID_TIMESTAMP) -> {
                    Timber.tag(TAG).e( // VALIDATOR-LOG
                        "[STAGE-3 VALIDATOR] DROP session=%s reason=end-before-threshold recordedAt=%d (%s) threshold=%d (%s)",
                        dateIso, endMs, UTC_FORMATTER.format(session.sleepEndMs),
                        THRESHOLD_EPOCH_MS, THRESHOLD_UTC_STRING
                    )
                    ValidationResult.Rejected(session, "sleepEndMs ${session.sleepEndMs} is before 2020-01-01")
                }
                endMs <= startMs -> {
                    Timber.tag(TAG).e( // VALIDATOR-LOG
                        "[STAGE-3 VALIDATOR] DROP session=%s reason=end-not-after-start startMs=%d endMs=%d",
                        dateIso, startMs, endMs
                    )
                    ValidationResult.Rejected(session, "sleepEndMs $endMs is not after sleepStartMs $startMs")
                }
                durationMs < 60_000L -> {
                    Timber.tag(TAG).e( // VALIDATOR-LOG
                        "[STAGE-3 VALIDATOR] DROP session=%s reason=duration-too-short durationMs=%d",
                        dateIso, durationMs
                    )
                    ValidationResult.Rejected(session, "sleep duration ${durationMs}ms is less than 1 minute")
                }
                durationMs > 86_400_000L -> {
                    Timber.tag(TAG).e( // VALIDATOR-LOG
                        "[STAGE-3 VALIDATOR] DROP session=%s reason=duration-exceeds-24h durationMs=%d",
                        dateIso, durationMs
                    )
                    ValidationResult.Rejected(session, "sleep duration ${durationMs}ms exceeds 24 hours")
                }
                session.sleepEndMs.isAfter(Instant.now().plusMillis(3_600_000L)) -> {
                    Timber.tag(TAG).e( // VALIDATOR-LOG
                        "[STAGE-3 VALIDATOR] DROP session=%s reason=end-in-future endMs=%d (%s)",
                        dateIso, endMs, UTC_FORMATTER.format(session.sleepEndMs)
                    )
                    ValidationResult.Rejected(session, "sleepEndMs ${session.sleepEndMs} is more than 1 hour in the future")
                }
                abs(actualMinutes - session.durationMinutes) > 2 -> {
                    Timber.tag(TAG).e( // VALIDATOR-LOG
                        "[STAGE-3 VALIDATOR] DROP session=%s reason=duration-mismatch stored=%d computed=%d",
                        dateIso, session.durationMinutes, actualMinutes
                    )
                    ValidationResult.Rejected(session, "durationMinutes ${session.durationMinutes} does not match computed $actualMinutes (tolerance 2 min)")
                }
                session.date.isAfter(LocalDate.now()) -> {
                    Timber.tag(TAG).e( // VALIDATOR-LOG
                        "[STAGE-3 VALIDATOR] DROP session=%s reason=date-in-future",
                        dateIso
                    )
                    ValidationResult.Rejected(session, "date ${session.date} is in the future")
                }
                else -> {
                    val expectedDate = session.sleepEndMs.atZone(ZoneId.systemDefault()).toLocalDate()
                    val corrected = if (session.date != expectedDate) {
                        Timber.w("Correcting sleep session date from ${session.date} to $expectedDate (sleepEndMs=${session.sleepEndMs})")
                        session.copy(date = expectedDate)
                    } else {
                        session
                    }
                    ValidationResult.Accepted(corrected)
                }
            }
            if (result is ValidationResult.Accepted) accepted++ else rejected++
            result
        }
        if (rejected > 0) { // VALIDATOR-LOG
            Timber.tag(TAG).e("[STAGE-3 VALIDATOR] sessions complete total=%d accepted=%d rejected=%d", sessions.size, accepted, rejected)
        } else {
            Timber.tag(TAG).d("[STAGE-3 VALIDATOR] sessions complete total=%d accepted=%d rejected=%d", sessions.size, accepted, rejected)
        }
        return results
    }

    fun validateActivities(activities: List<Activity>): List<ValidationResult<Activity>> {
        var accepted = 0
        var rejected = 0
        val results = activities.map { activity ->
            val startMs = activity.startTime.toEpochMilli()
            val result: ValidationResult<Activity> = when {
                activity.startTime.isBefore(EARLIEST_VALID_TIMESTAMP) -> {
                    Timber.tag(TAG).e( // VALIDATOR-LOG
                        "[STAGE-3 VALIDATOR] DROP activity=%s reason=start-before-threshold recordedAt=%d (%s) threshold=%d (%s)",
                        activity.deviceName, startMs, UTC_FORMATTER.format(activity.startTime),
                        THRESHOLD_EPOCH_MS, THRESHOLD_UTC_STRING
                    )
                    ValidationResult.Rejected(activity, "startTimeMs=${activity.startTime.toEpochMilli()} fails floor check")
                }
                activity.startTime.isAfter(Instant.now().plusSeconds(3_600)) -> {
                    Timber.tag(TAG).e( // VALIDATOR-LOG
                        "[STAGE-3 VALIDATOR] DROP activity=%s reason=start-in-future recordedAt=%d (%s)",
                        activity.deviceName, startMs, UTC_FORMATTER.format(activity.startTime)
                    )
                    ValidationResult.Rejected(activity, "startTimeMs=${activity.startTime} is more than 1 hour in the future")
                }
                !activity.endTime.isAfter(activity.startTime) -> {
                    Timber.tag(TAG).e( // VALIDATOR-LOG
                        "[STAGE-3 VALIDATOR] DROP activity=%s reason=end-not-after-start startMs=%d endMs=%d",
                        activity.deviceName, startMs, activity.endTime.toEpochMilli()
                    )
                    ValidationResult.Rejected(activity, "endTime ${activity.endTime} is not after startTime ${activity.startTime}")
                }
                activity.durationMinutes <= 0 -> {
                    Timber.tag(TAG).e( // VALIDATOR-LOG
                        "[STAGE-3 VALIDATOR] DROP activity=%s reason=non-positive-duration durationMinutes=%d",
                        activity.deviceName, activity.durationMinutes
                    )
                    ValidationResult.Rejected(activity, "durationMinutes must be > 0, got ${activity.durationMinutes}")
                }
                activity.deviceName.isBlank() -> {
                    Timber.tag(TAG).e("[STAGE-3 VALIDATOR] DROP activity=<blank> reason=blank-device-name") // VALIDATOR-LOG
                    ValidationResult.Rejected(activity, "deviceName is blank")
                }
                else -> ValidationResult.Accepted(activity)
            }
            if (result is ValidationResult.Accepted) accepted++ else rejected++
            result
        }
        if (rejected > 0) { // VALIDATOR-LOG
            Timber.tag(TAG).e("[STAGE-3 VALIDATOR] activities complete total=%d accepted=%d rejected=%d", activities.size, accepted, rejected)
        } else {
            Timber.tag(TAG).d("[STAGE-3 VALIDATOR] activities complete total=%d accepted=%d rejected=%d", activities.size, accepted, rejected)
        }
        return results
    }
}
