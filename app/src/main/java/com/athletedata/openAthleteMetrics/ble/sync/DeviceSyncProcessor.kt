package com.athletedata.openAthleteMetrics.ble.sync

import androidx.room.withTransaction
import com.athletedata.openAthleteMetrics.ble.driver.DriverRegistry
import com.athletedata.openAthleteMetrics.data.db.AppDatabase
import com.athletedata.openAthleteMetrics.data.model.Activity
import com.athletedata.openAthleteMetrics.data.model.Device
import com.athletedata.openAthleteMetrics.data.model.DriverSyncResult
import com.athletedata.openAthleteMetrics.data.model.MetricReading
import com.athletedata.openAthleteMetrics.data.model.MetricType
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

@Singleton
class DeviceSyncProcessor @Inject constructor(
    private val appDatabase: AppDatabase,
    private val metricRepository: MetricRepository,
    private val sleepRepository: SleepRepository,
    private val activityRepository: ActivityRepository,
    private val syncSessionRepository: SyncSessionRepository,
    private val rawDeviceDataRepository: RawDeviceDataRepository,
    private val deviceRepository: DeviceRepository,
    private val driverRegistry: DriverRegistry,
    private val validator: SyncValidator,
) {

    private val pruneScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun process(result: DriverSyncResult): SyncSummary {
        val driverDisplayName = driverRegistry.allDrivers()
            .find { it.id == result.driverId }
            ?.displayName ?: result.driverId

        val existing = deviceRepository.getDeviceByAddress(result.deviceId)
        val device = if (existing != null) {
            if (existing.displayName != driverDisplayName) {
                deviceRepository.upsert(existing.copy(displayName = driverDisplayName))
            }
            existing
        } else {
            deviceRepository.upsert(
                Device(
                    bleAddress = result.deviceId,
                    driverId = result.driverId,
                    displayName = driverDisplayName,
                )
            )
            deviceRepository.getDeviceByAddress(result.deviceId)!!
        }

        // Log retry intent if the last sync for this device failed (expected after a crash).
        syncSessionRepository.getLatestSessionForDevice(device.id)
            ?.takeIf { it.status == SyncStatus.FAILED }
            ?.let { Timber.i("Retrying after failed sync at ${it.startedAt}") }

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
                .filter { it.metricType != MetricType.BATTERY }
            val acceptedSessions = sessionResults
                .filterIsInstance<ValidationResult.Accepted<SleepSession>>()
                .map { it.item }
            val acceptedActivities = activityResults
                .filterIsInstance<ValidationResult.Accepted<Activity>>()
                .map { it.item }

            // c-f. Persist accepted data and raw payloads atomically.
            // For each (driverId, date) group, prepend the existing DB record so the merge
            // always produces a superset — partial records from prior syncs are never left behind.
            val sessionsWithExisting = acceptedSessions
                .groupBy { Pair(it.driverId, it.date) }
                .flatMap { (key, incoming) ->
                    val (driverId, date) = key
                    val existing = if (driverId != null) {
                        sleepRepository.getByDriverAndDate(driverId, date)
                    } else null
                    listOfNotNull(existing) + incoming
                }
            val mergedSessions = mergeSleepSessions(sessionsWithExisting)
            val (readingsSkipped, activitiesSkipped) = appDatabase.withTransaction {
                val rs = metricRepository.insertAllFromDevice(acceptedReadings)
                mergedSessions.forEach { sleepRepository.insertOrReplace(it) }
                val `as` = activityRepository.insertAllFromDevice(acceptedActivities)
                rawDeviceDataRepository.insertAll(result.rawPayloads, syncSessionId)
                rs to `as`
            }

            // g. Compute final counts and status (battery readings are not counted — they go to device metadata).
            val readingsAccepted = acceptedReadings.size
            val readingsInserted = readingsAccepted - readingsSkipped
            val healthReadingsTotal = result.metricReadings.count { it.metricType != MetricType.BATTERY }
            val readingsRejected = healthReadingsTotal - readingsAccepted
            val sessionsAccepted = acceptedSessions.size
            val sessionsInserted = mergedSessions.size
            val sessionsSkippedCount = 0
            val sessionsRejected = result.sleepSessions.size - acceptedSessions.size
            val activitiesAccepted = acceptedActivities.size
            val activitiesInserted = activitiesAccepted - activitiesSkipped
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

            // i. Prune stale rows (fire-and-forget — pruning failure is not a sync failure).
            pruneScope.launch {
                val now = Instant.now()
                runCatching {
                    rawDeviceDataRepository.deleteOlderThan(now.minusMillis(7L * 24 * 60 * 60 * 1000))
                }.onFailure { Timber.w(it, "raw_device_data pruning failed") }
                runCatching {
                    syncSessionRepository.deleteOlderThan(now.minusMillis(90L * 24 * 60 * 60 * 1000))
                }.onFailure { Timber.w(it, "sync_sessions pruning failed") }
            }

            // j. Stamp the device with the sync time and last known battery.
            // Use lastOrNull() (BLE arrival order) rather than maxByOrNull { recordedAt } so that
            // RTC drift or a future device timestamp cannot promote a stale reading.
            // Relies on metricReadings being a List that preserves BLE notification order.
            deviceRepository.updateLastSync(result.deviceId, result.syncEndedAt.toEpochMilli())
            result.metricReadings
                .filter { it.metricType == MetricType.BATTERY }
                .lastOrNull()
                ?.let { deviceRepository.updateLastBatteryPct(result.deviceId, it.value.toInt()) }

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
                readingsInserted = readingsInserted,
                readingsRejected = readingsRejected,
                readingsSkipped = readingsSkipped,
                sessionsAccepted = sessionsAccepted,
                sessionsInserted = sessionsInserted,
                sessionsRejected = sessionsRejected,
                sessionsSkipped = sessionsSkippedCount,
                activitiesAccepted = activitiesAccepted,
                activitiesInserted = activitiesInserted,
                activitiesRejected = activitiesRejected,
                activitiesSkipped = activitiesSkipped,
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
