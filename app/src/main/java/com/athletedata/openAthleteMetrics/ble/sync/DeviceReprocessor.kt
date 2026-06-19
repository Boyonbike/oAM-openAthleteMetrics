package com.athletedata.openAthleteMetrics.ble.sync

import androidx.room.withTransaction
import androidx.work.WorkManager
import com.athletedata.openAthleteMetrics.ble.driver.DriverRegistry
import com.athletedata.openAthleteMetrics.data.db.AppDatabase
import com.athletedata.openAthleteMetrics.data.model.Activity
import com.athletedata.openAthleteMetrics.data.model.Device
import com.athletedata.openAthleteMetrics.data.model.MetricReading
import com.athletedata.openAthleteMetrics.data.model.MetricType
import com.athletedata.openAthleteMetrics.data.model.ReprocessSummary
import com.athletedata.openAthleteMetrics.data.model.SleepSession
import com.athletedata.openAthleteMetrics.data.model.SyncSession
import com.athletedata.openAthleteMetrics.data.model.SyncStatus
import com.athletedata.openAthleteMetrics.data.repository.ActivityRepository
import com.athletedata.openAthleteMetrics.data.repository.RawDeviceDataRepository
import com.athletedata.openAthleteMetrics.data.repository.SleepRepository
import com.athletedata.openAthleteMetrics.data.repository.SyncSessionRepository
import com.athletedata.openAthleteMetrics.worker.SleepStagePromoter
import com.athletedata.openAthleteMetrics.worker.enqueueSummaryWorker
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceReprocessor @Inject constructor(
    private val appDatabase: AppDatabase,
    private val driverRegistry: DriverRegistry,
    private val rawDeviceDataRepository: RawDeviceDataRepository,
    private val syncSessionRepository: SyncSessionRepository,
    private val metricRouter: MetricRouter,
    private val sleepRepository: SleepRepository,
    private val activityRepository: ActivityRepository,
    private val validator: SyncValidator,
    private val workManager: WorkManager,
    private val sleepStagePromoter: SleepStagePromoter,
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

        val reprocessStartedAt = Instant.now()
        val syncSessionId = syncSessionRepository.insert(
            SyncSession(
                deviceId = device.id,
                driverId = device.driverId,
                startedAt = reprocessStartedAt,
                status = SyncStatus.IN_PROGRESS,
                recordsImported = 0,
                packetsReceived = rawPayloads.size,
            )
        )

        try {
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

            val readingResults = validator.validateReadings(allReadings)
            val sessionResults = validator.validateSessions(allSleepSessions)
            val activityResults = validator.validateActivities(allActivities)

            val acceptedReadings = readingResults
                .filterIsInstance<ValidationResult.Accepted<MetricReading>>()
                .map { it.item }
                .filter { it.metricType != MetricType.BATTERY }
            val acceptedSessions = sessionResults
                .filterIsInstance<ValidationResult.Accepted<SleepSession>>()
                .map { it.item }
            val acceptedActivities = activityResults
                .filterIsInstance<ValidationResult.Accepted<Activity>>()
                .map { it.item }

            onProgress(0.85f)

            val mergedSessions = mergeSleepSessions(acceptedSessions)

            val activitiesReplaced = appDatabase.withTransaction {
                metricRouter.routeAllForceReplace(acceptedReadings)
                mergedSessions.forEach { sleepRepository.insertOrReplace(it) }
                activityRepository.replaceAllFromDevice(acceptedActivities)
            }

            val totalAccepted = acceptedReadings.size + acceptedSessions.size + acceptedActivities.size
            val totalRejected =
                (allReadings.count { it.metricType != MetricType.BATTERY } - acceptedReadings.size) +
                (allSleepSessions.size - acceptedSessions.size) +
                (allActivities.size - acceptedActivities.size)
            val finalStatus = when {
                totalRejected == 0 -> SyncStatus.SUCCESS
                totalAccepted == 0 -> SyncStatus.FAILED
                else -> SyncStatus.PARTIAL
            }

            val reprocessEndedAt = Instant.now()
            sleepStagePromoter.promote(
                driverId = device.driverId,
                syncWindowStartMs = since.toEpochMilli(),
                syncWindowEndMs = reprocessEndedAt.toEpochMilli(),
            )

            val recordsReplaced = acceptedReadings.size + mergedSessions.size + activitiesReplaced
            syncSessionRepository.update(
                SyncSession(
                    id = syncSessionId,
                    deviceId = device.id,
                    driverId = device.driverId,
                    startedAt = reprocessStartedAt,
                    endedAt = reprocessEndedAt,
                    status = finalStatus,
                    recordsImported = recordsReplaced,
                    packetsReceived = rawPayloads.size,
                )
            )

            onProgress(0.95f)

            val metricDates = acceptedReadings
                .map { it.recordedAt.atZone(ZoneId.systemDefault()).toLocalDate() }.toSet()
            val activityDates = acceptedActivities
                .map { it.startTime.atZone(ZoneId.systemDefault()).toLocalDate() }.toSet()
            val sleepDates = mergedSessions.map { it.date }.toSet()
            val allDates = metricDates + activityDates + sleepDates

            // Enqueue summary workers for metric + activity dates (sleep dates already triggered by insertOrReplace).
            (metricDates + activityDates).forEach { enqueueSummaryWorker(it, workManager) }

            onProgress(1f)

            Timber.i("DeviceReprocessor: replaced $recordsReplaced records across ${allDates.size} dates for device ${device.id}")

            return ReprocessSummary(recordsReplaced = recordsReplaced, datesAffected = allDates)

        } catch (e: Exception) {
            runCatching {
                syncSessionRepository.update(
                    SyncSession(
                        id = syncSessionId,
                        deviceId = device.id,
                        driverId = device.driverId,
                        startedAt = reprocessStartedAt,
                        endedAt = Instant.now(),
                        status = SyncStatus.FAILED,
                        recordsImported = 0,
                        errorMessage = e.message,
                    )
                )
            }
            throw e
        }
    }
}
