package com.athletedata.openAthleteMetrics.ble.sync

import com.athletedata.openAthleteMetrics.data.model.Activity
import com.athletedata.openAthleteMetrics.data.model.Device
import com.athletedata.openAthleteMetrics.data.model.DriverSyncResult
import com.athletedata.openAthleteMetrics.data.model.MetricReading
import com.athletedata.openAthleteMetrics.data.model.SleepSession
import com.athletedata.openAthleteMetrics.data.model.SyncSession
import com.athletedata.openAthleteMetrics.data.model.SyncStatus
import com.athletedata.openAthleteMetrics.data.repository.ActivityRepository
import com.athletedata.openAthleteMetrics.data.repository.DeviceRepository
import com.athletedata.openAthleteMetrics.data.repository.MetricRepository
import com.athletedata.openAthleteMetrics.data.repository.RawDeviceDataRepository
import com.athletedata.openAthleteMetrics.data.repository.SleepRepository
import com.athletedata.openAthleteMetrics.data.repository.SyncSessionRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceSyncProcessor @Inject constructor(
    private val metricRepository: MetricRepository,
    private val sleepRepository: SleepRepository,
    private val activityRepository: ActivityRepository,
    private val syncSessionRepository: SyncSessionRepository,
    private val rawDeviceDataRepository: RawDeviceDataRepository,
    private val deviceRepository: DeviceRepository,
    private val validator: SyncValidator,
) {

    suspend fun process(result: DriverSyncResult): SyncSummary {
        val device = deviceRepository.getDeviceByAddress(result.deviceId)
            ?: run {
                deviceRepository.upsert(
                    Device(
                        bleAddress = result.deviceId,
                        driverId = result.driverId,
                        displayName = result.deviceId,
                    )
                )
                deviceRepository.getDeviceByAddress(result.deviceId)!!
            }

        // a. Record sync attempt immediately so a mid-sync crash is still visible.
        val syncSessionId = syncSessionRepository.insert(
            SyncSession(
                deviceId = device.id,
                driverId = result.driverId,
                startedAt = result.syncStartedAt,
                status = SyncStatus.PARTIAL,
                recordsImported = 0,
            )
        )

        try {
            // b. Validate all three data types.
            val readingResults = validator.validateReadings(result.metricReadings)
            val sessionResults = validator.validateSessions(result.sleepSessions)
            val activityResults = validator.validateActivities(result.activities)

            val acceptedReadings = readingResults
                .filterIsInstance<ValidationResult.Accepted<MetricReading>>()
                .map { it.item }
            val acceptedSessions = sessionResults
                .filterIsInstance<ValidationResult.Accepted<SleepSession>>()
                .map { it.item }
            val acceptedActivities = activityResults
                .filterIsInstance<ValidationResult.Accepted<Activity>>()
                .map { it.item }

            // c-f. Persist accepted data and raw payloads.
            metricRepository.insertAllFromDevice(acceptedReadings)
            acceptedSessions.forEach { sleepRepository.insert(it) }
            activityRepository.insertAll(acceptedActivities)
            rawDeviceDataRepository.insertAll(result.rawPayloads, syncSessionId)

            // g. Compute final counts and status.
            val readingsAccepted = acceptedReadings.size
            val readingsRejected = result.metricReadings.size - readingsAccepted
            val sessionsAccepted = acceptedSessions.size
            val sessionsRejected = result.sleepSessions.size - sessionsAccepted
            val activitiesAccepted = acceptedActivities.size
            val activitiesRejected = result.activities.size - activitiesAccepted
            val totalAccepted = readingsAccepted + sessionsAccepted + activitiesAccepted
            val totalRejected = readingsRejected + sessionsRejected + activitiesRejected

            val finalStatus = when {
                totalRejected == 0 -> SyncStatus.SUCCESS
                totalAccepted == 0 -> SyncStatus.FAILED
                else -> SyncStatus.PARTIAL
            }

            // h. Update sync session with final state.
            syncSessionRepository.update(
                SyncSession(
                    id = syncSessionId,
                    deviceId = device.id,
                    driverId = result.driverId,
                    startedAt = result.syncStartedAt,
                    endedAt = result.syncEndedAt,
                    status = finalStatus,
                    recordsImported = totalAccepted,
                )
            )

            // i. Stamp the device with the sync time.
            deviceRepository.updateLastSync(result.deviceId, result.syncEndedAt.toEpochMilli())

            val rejectionReasons = buildList {
                readingResults.filterIsInstance<ValidationResult.Rejected<MetricReading>>()
                    .forEach { add(it.reason) }
                sessionResults.filterIsInstance<ValidationResult.Rejected<SleepSession>>()
                    .forEach { add(it.reason) }
                activityResults.filterIsInstance<ValidationResult.Rejected<Activity>>()
                    .forEach { add(it.reason) }
            }

            return SyncSummary(
                readingsAccepted = readingsAccepted,
                readingsRejected = readingsRejected,
                sessionsAccepted = sessionsAccepted,
                sessionsRejected = sessionsRejected,
                activitiesAccepted = activitiesAccepted,
                activitiesRejected = activitiesRejected,
                rejectionReasons = rejectionReasons,
                finalStatus = finalStatus,
            )

        } catch (e: Exception) {
            runCatching {
                syncSessionRepository.update(
                    SyncSession(
                        id = syncSessionId,
                        deviceId = device.id,
                        driverId = result.driverId,
                        startedAt = result.syncStartedAt,
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
