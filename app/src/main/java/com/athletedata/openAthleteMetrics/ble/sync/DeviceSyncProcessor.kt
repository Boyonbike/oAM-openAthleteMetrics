package com.athletedata.openAthleteMetrics.ble.sync

import androidx.room.withTransaction
import com.athletedata.openAthleteMetrics.ble.driver.DriverRegistry
import com.athletedata.openAthleteMetrics.data.db.AppDatabase
import com.athletedata.openAthleteMetrics.data.model.Activity
import com.athletedata.openAthleteMetrics.data.model.Device
import com.athletedata.openAthleteMetrics.data.model.DriverSyncResult
import com.athletedata.openAthleteMetrics.data.model.MetricType
import com.athletedata.openAthleteMetrics.data.model.SleepSession
import com.athletedata.openAthleteMetrics.data.model.SyncSession
import com.athletedata.openAthleteMetrics.data.model.SyncStatus
import com.athletedata.openAthleteMetrics.data.repository.ActivityRepository
import com.athletedata.openAthleteMetrics.data.repository.DeviceRepository
import com.athletedata.openAthleteMetrics.domain.usecase.SyncActivityUseCase
import com.athletedata.openAthleteMetrics.data.repository.RawDeviceDataRepository
import com.athletedata.openAthleteMetrics.data.repository.SleepRepository
import com.athletedata.openAthleteMetrics.data.repository.SyncSessionRepository
import com.athletedata.openAthleteMetrics.worker.SleepStagePromoter
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

private const val TAG = "data-pathway-tracker" // DPT

@Singleton
class DeviceSyncProcessor @Inject constructor(
    private val appDatabase: AppDatabase,
    private val sleepRepository: SleepRepository,
    private val activityRepository: ActivityRepository,
    private val syncActivityUseCase: SyncActivityUseCase,
    private val syncSessionRepository: SyncSessionRepository,
    private val rawDeviceDataRepository: RawDeviceDataRepository,
    private val deviceRepository: DeviceRepository,
    private val driverRegistry: DriverRegistry,
    private val validator: SyncValidator,
    // REMOVED: dead-code-archaeology
    private val sleepStagePromoter: SleepStagePromoter,
) {

    private val pruneScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun shutdown() {
        pruneScope.cancel()
    }

    /**
     * Creates (or finds) the device row and inserts an IN_PROGRESS SyncSession sentinel.
     * Called from BleEngine on the first assembled packet so raw writes have a session ID
     * before triggerSync() is ever invoked.
     */
    suspend fun beginSession(
        deviceAddress: String,
        driverId: String,
        syncStartedAt: Instant,
    ): Long {
        val device = findOrCreateDevice(deviceAddress, driverId)
        return syncSessionRepository.insert(
            SyncSession(
                deviceId = device.id,
                driverId = driverId,
                startedAt = syncStartedAt,
                status = SyncStatus.IN_PROGRESS,
                recordsImported = 0,
            )
        )
    }

    /**
     * Processes a completed DriverSyncResult.
     *
     * If [preSyncSessionId] is non-null the session row already exists (created by
     * [beginSession] and the device record is guaranteed to be present); this path
     * skips device-creation and session-insert and uses the supplied ID.
     */
    suspend fun process(result: DriverSyncResult, preSyncSessionId: Long? = null): SyncSummary { // METRIC-OWNERSHIP
        // Metric readings are written immediately per-notification by MetricRouter.route().
        // DeviceSyncProcessor handles only: sleep sessions, activities, sleep stage
        // promotion, sync session accounting, device metadata, and raw data persistence.
        val device: Device
        val syncSessionId: Long

        if (preSyncSessionId != null) {
            // Device and session were already created when the first packet arrived.
            device = deviceRepository.getDeviceByAddress(result.deviceId)!!
            syncSessionId = preSyncSessionId
        } else {
            // Legacy path: no pre-existing session (e.g. called without BLE accumulation).
            device = findOrCreateDevice(result.deviceId, result.driverId)

            // Log retry intent if the last sync for this device failed (expected after a crash).
            syncSessionRepository.getLatestSessionForDevice(device.id)
                ?.takeIf { it.status == SyncStatus.FAILED }
                ?.let { Timber.i("Retrying after failed sync at ${it.startedAt}") }

            // a. Record sync attempt immediately so a mid-sync crash is still visible.
            syncSessionId = syncSessionRepository.insert(
                SyncSession(
                    deviceId = device.id,
                    driverId = result.driverId,
                    startedAt = result.syncStartedAt,
                    status = SyncStatus.IN_PROGRESS,
                    recordsImported = 0,
                    packetsReceived = result.packetsReceived,
                    // REMOVED: early-sync-warning
                )
            )
        }

        try {
            // b. Validate sessions and activities (readings are pre-committed by MetricRouter).
            // REMOVED: double-write — validator.validateReadings() and acceptedReadings removed.
            val sessionResults = validator.validateSessions(result.sleepSessions)
            val activityResults = validator.validateActivities(result.activities)

            val acceptedSessions = sessionResults
                .filterIsInstance<ValidationResult.Accepted<SleepSession>>()
                .map { it.item }
            val acceptedActivities = activityResults
                .filterIsInstance<ValidationResult.Accepted<Activity>>()
                .map { it.item }

            // c-f. Persist sessions, activities, and raw payloads atomically.
            val mergedSessions = buildMergedSessions(acceptedSessions)
            val activitiesSkipped =
                appDatabase.withTransaction {
                    mergedSessions.forEach { sleepRepository.insertOrReplace(it) }
                    val skipped = syncActivityUseCase.execute(acceptedActivities)
                    // rawPayloads is empty when packets were persisted on arrival (Fix 18);
                    // non-empty only on the legacy path where BleEngine held them in memory.
                    rawDeviceDataRepository.insertAll(result.rawPayloads, syncSessionId)
                    skipped
                }

            Timber.tag(TAG).d("[STAGE-6 DB-WRITE] SUCCESS — sessions=%d activities=%d", mergedSessions.size, acceptedActivities.size - activitiesSkipped) // DPT / METRIC-OWNERSHIP
            // g. Compute final counts and status (based on sessions + activities only).
            val sessionsAccepted = acceptedSessions.size
            val sessionsInserted = mergedSessions.size
            val sessionsRejected = result.sleepSessions.size - sessionsAccepted
            val activitiesAccepted = acceptedActivities.size
            val activitiesInserted = activitiesAccepted - activitiesSkipped
            val activitiesRejected = result.activities.size - activitiesAccepted
            val totalAccepted = sessionsAccepted + activitiesAccepted // METRIC-OWNERSHIP
            val totalRejected = sessionsRejected + activitiesRejected // METRIC-OWNERSHIP

            val finalStatus = when {
                totalRejected == 0 -> SyncStatus.SUCCESS
                totalAccepted == 0 -> SyncStatus.FAILED
                else -> SyncStatus.PARTIAL
            }

            val genuinelyNew = sessionsInserted + activitiesInserted // METRIC-OWNERSHIP

            // h. Update sync session with final state.
            syncSessionRepository.update(
                SyncSession(
                    id = syncSessionId,
                    deviceId = device.id,
                    driverId = result.driverId,
                    startedAt = result.syncStartedAt,
                    endedAt = result.syncEndedAt,
                    status = finalStatus,
                    recordsImported = genuinelyNew,
                    packetsReceived = result.packetsReceived,
                    // REMOVED: early-sync-warning
                )
            )

            sleepStagePromoter.promote(
                driverId = result.driverId,
                syncWindowStartMs = result.syncStartedAt.toEpochMilli(),
                syncWindowEndMs = result.syncEndedAt.toEpochMilli(),
            )

            schedulePrune()

            // j. Stamp the device with the sync time and last known battery.
            deviceRepository.updateLastSync(result.deviceId, result.syncEndedAt.toEpochMilli())
            result.metricReadings
                .filter { it.metricType == MetricType.BATTERY }
                .lastOrNull()
                ?.let { deviceRepository.updateLastBatteryPct(result.deviceId, it.value.toInt()) }

            val rejectionReasons = buildList {
                // REMOVED: double-write — reading rejection reasons removed.
                sessionResults.filterIsInstance<ValidationResult.Rejected<SleepSession>>()
                    .forEach { add(it.reason) }
                activityResults.filterIsInstance<ValidationResult.Rejected<Activity>>()
                    .forEach { add(it.reason) }
            }

            Timber.i(
                "SyncSummary: sessions inserted=%d | activities inserted=%d skipped=%d | status=%s | packetsReceived=%d",
                sessionsInserted,
                activitiesInserted, activitiesSkipped,
                finalStatus,
                result.packetsReceived,
                // REMOVED: early-sync-warning — syncedBeforeQuiescence removed from log
            )

            return SyncSummary(
                newRecordsInserted = 0, // METRIC-OWNERSHIP: readings written per-notification by MetricRouter
                accumulatorUpdates = 0, // METRIC-OWNERSHIP
                accumulatorNoChange = 0, // METRIC-OWNERSHIP
                accumulatorGuarded = 0, // METRIC-OWNERSHIP
                readingsSkipped = 0, // METRIC-OWNERSHIP
                sessionsInserted = sessionsInserted,
                activitiesInserted = activitiesInserted,
                activitiesSkipped = activitiesSkipped,
                rejectionReasons = rejectionReasons,
                finalStatus = finalStatus,
                packetsReceived = result.packetsReceived,
                // REMOVED: early-sync-warning
            )

        } catch (e: Exception) {
            Timber.tag(TAG).e("[STAGE-6 DB-WRITE] ERROR — %s", e.message) // DPT
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

    // REMOVED: interrupted-sync-recovery — processFromRaw() deleted.
    // Raw packets are still persisted on arrival by BleEngine for durability;
    // the raw_device_data table has no recovery reader after this change.

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun findOrCreateDevice(bleAddress: String, driverId: String): Device {
        val driverDisplayName = driverRegistry.allDrivers()
            .find { it.id == driverId }?.displayName ?: driverId
        val existing = deviceRepository.getDeviceByAddress(bleAddress)
        return if (existing != null) {
            if (existing.displayName != driverDisplayName) {
                deviceRepository.upsert(existing.copy(displayName = driverDisplayName))
            }
            existing
        } else {
            deviceRepository.upsert(
                Device(
                    bleAddress = bleAddress,
                    driverId = driverId,
                    displayName = driverDisplayName,
                )
            )
            deviceRepository.getDeviceByAddress(bleAddress)!!
        }
    }

    private suspend fun buildMergedSessions(accepted: List<SleepSession>): List<SleepSession> {
        val sessionsWithExisting = accepted
            .groupBy { Pair(it.driverId, it.date) }
            .flatMap { (key, incoming) ->
                val (driverId, date) = key
                val existing = if (driverId != null) {
                    sleepRepository.getByDriverAndDate(driverId, date)
                } else null
                listOfNotNull(existing) + incoming
            }
        return mergeSleepSessions(sessionsWithExisting)
    }

    private fun schedulePrune() {
        pruneScope.launch {
            val now = Instant.now()
            runCatching {
                rawDeviceDataRepository.deleteOlderThan(now.minusMillis(7L * 24 * 60 * 60 * 1000))
            }.onFailure { Timber.w(it, "raw_device_data pruning failed") }
            runCatching {
                syncSessionRepository.deleteOlderThan(now.minusMillis(90L * 24 * 60 * 60 * 1000))
            }.onFailure { Timber.w(it, "sync_sessions pruning failed") }
        }
    }
}
