package com.athletedata.openAthleteMetrics.ble.sync

import androidx.work.WorkManager
import com.athletedata.openAthleteMetrics.ble.driver.DriverRegistry
import com.athletedata.openAthleteMetrics.data.model.Activity
import com.athletedata.openAthleteMetrics.data.model.Device
import com.athletedata.openAthleteMetrics.data.model.MetricReading
import com.athletedata.openAthleteMetrics.data.model.MetricType
import com.athletedata.openAthleteMetrics.data.model.ReprocessSummary
import com.athletedata.openAthleteMetrics.data.model.SleepSession
import com.athletedata.openAthleteMetrics.data.repository.ActivityRepository
import com.athletedata.openAthleteMetrics.data.repository.MetricReadingStagingRepository
import com.athletedata.openAthleteMetrics.data.repository.RawDeviceDataRepository
import com.athletedata.openAthleteMetrics.data.repository.SleepRepository
import com.athletedata.openAthleteMetrics.worker.enqueueSummaryWorker
import timber.log.Timber
import java.time.Instant
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceReprocessor @Inject constructor(
    private val driverRegistry: DriverRegistry,
    private val rawDeviceDataRepository: RawDeviceDataRepository,
    private val metricRepository: MetricReadingStagingRepository,
    private val sleepRepository: SleepRepository,
    private val activityRepository: ActivityRepository,
    private val validator: SyncValidator,
    private val workManager: WorkManager,
) {
    /**
     * Re-parses all stored raw BLE payloads for [device] received since [since],
     * using the currently registered driver, and force-replaces stored records.
     * [onProgress] is called with values in [0f, 1f].
     *
     * Must only be called when BLE is idle — the caller (ViewModel) enforces this.
     */
    suspend fun reprocess(
        device: Device,
        since: Instant,
        onProgress: (Float) -> Unit,
    ): ReprocessSummary {
        val manifest = driverRegistry.allDrivers().find { it.id == device.driverId }
            ?: return ReprocessSummary(
                recordsReplaced = 0,
                datesAffected = emptySet(),
                error = "Driver '${device.driverId}' not loaded",
            )

        val rawPayloads = rawDeviceDataRepository.getForDevice(device.id, since)
        if (rawPayloads.isEmpty()) {
            return ReprocessSummary(recordsReplaced = 0, datesAffected = emptySet())
        }

        val allReadings = mutableListOf<MetricReading>()
        val allSleepSessions = mutableListOf<SleepSession>()
        val allActivities = mutableListOf<Activity>()

        rawPayloads.forEachIndexed { index, payload ->
            onProgress((index.toFloat() / rawPayloads.size) * 0.80f)
            val uuid = payload.characteristicUuid
            val bytes = payload.payload
            allReadings += driverRegistry.parseMetrics(manifest, uuid, bytes)
            driverRegistry.parseSleep(manifest, uuid, bytes)?.let { allSleepSessions += it }
            driverRegistry.parseActivity(manifest, uuid, bytes)?.let { allActivities += it }
        }
        onProgress(0.80f)

        val acceptedReadings = validator.validateReadings(allReadings)
            .filterIsInstance<ValidationResult.Accepted<MetricReading>>()
            .map { it.item }
            .filter { it.metricType != MetricType.BATTERY }

        val acceptedSessions = validator.validateSessions(allSleepSessions)
            .filterIsInstance<ValidationResult.Accepted<SleepSession>>()
            .map { it.item }

        val acceptedActivities = validator.validateActivities(allActivities)
            .filterIsInstance<ValidationResult.Accepted<Activity>>()
            .map { it.item }

        onProgress(0.85f)

        val mergedSessions = mergeSleepSessions(acceptedSessions)

        val metricsReplaced = metricRepository.replaceAllFromDevice(acceptedReadings)
        mergedSessions.forEach { sleepRepository.insertOrReplace(it) }   // already enqueues summary worker
        val activitiesReplaced = activityRepository.replaceAllFromDevice(acceptedActivities)

        onProgress(0.95f)

        val metricDates = acceptedReadings
            .map { it.recordedAt.atZone(ZoneOffset.UTC).toLocalDate() }.toSet()
        val activityDates = acceptedActivities
            .map { it.startTime.atZone(ZoneOffset.UTC).toLocalDate() }.toSet()
        val sleepDates = mergedSessions.map { it.date }.toSet()
        val allDates = metricDates + activityDates + sleepDates

        // Enqueue summary workers for metric + activity dates (sleep dates already triggered by insertOrReplace).
        (metricDates + activityDates).forEach { enqueueSummaryWorker(it, workManager) }

        onProgress(1f)

        val total = metricsReplaced + mergedSessions.size + activitiesReplaced
        Timber.i("DeviceReprocessor: replaced $total records across ${allDates.size} dates for device ${device.id}")

        return ReprocessSummary(recordsReplaced = total, datesAffected = allDates)
    }
}
