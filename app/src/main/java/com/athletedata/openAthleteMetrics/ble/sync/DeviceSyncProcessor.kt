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
import com.athletedata.openAthleteMetrics.domain.usecase.SyncActivityUseCase
import com.athletedata.openAthleteMetrics.data.repository.MetricReadingStagingRepository
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

@Singleton
class DeviceSyncProcessor @Inject constructor(
    private val appDatabase: AppDatabase,
    private val metricRepository: MetricReadingStagingRepository,
    private val sleepRepository: SleepRepository,
    private val activityRepository: ActivityRepository,
    private val syncActivityUseCase: SyncActivityUseCase,
    private val syncSessionRepository: SyncSessionRepository,
    private val rawDeviceDataRepository: RawDeviceDataRepository,
    private val deviceRepository: DeviceRepository,
    private val driverRegistry: DriverRegistry,
    private val validator: SyncValidator,
    private val metricRouter: MetricRouter,
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
    suspend fun process(result: DriverSyncResult, preSyncSessionId: Long? = null): SyncSummary {
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
                    syncedBeforeQuiescence = result.syncedBeforeQuiescence,
                )
            )
        }

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

            val dedicatedTypes = setOf(
                MetricType.HR, MetricType.HRV, MetricType.SPO2,
                MetricType.RESPIRATION, MetricType.SKIN_TEMP, MetricType.STEPS,
                MetricType.ACTIVE_CALORIES, MetricType.TOTAL_CALORIES,
                MetricType.BLOOD_PRESSURE, MetricType.GLUCOSE, MetricType.SLEEP_STAGE,
            )
            val misfiled = acceptedReadings.filter { it.metricType in dedicatedTypes }
            if (misfiled.isNotEmpty()) {
                Timber.e(
                    "INVARIANT VIOLATION: acceptedReadings contains %d dedicated-type reading(s) " +
                    "that should have been routed by MetricRouter before reaching process(). " +
                    "Types: %s. These will be misfiled into the staging table.",
                    misfiled.size,
                    misfiled.map { it.metricType }.distinct(),
                )
            }

            // c-f. Persist accepted data and raw payloads atomically.
            val mergedSessions = buildMergedSessions(acceptedSessions)
            val (deviceInsert, activitiesSkipped) =
                appDatabase.withTransaction {
                    // INVARIANT: acceptedReadings here must contain only non-dedicated
                    // MetricTypes. Dedicated types (HR, HRV, SPO2, etc.) are routed to their
                    // typed tables by BleEngine.handleNotification() -> MetricRouter.route()
                    // BEFORE reaching pendingMetrics. If this function is ever called with
                    // unrouted dedicated-type readings, they will be silently misfiled into
                    // the staging table. Do not remove this comment without addressing that
                    // risk.
                    val rs = metricRepository.insertAllFromDevice(acceptedReadings)
                    mergedSessions.forEach { sleepRepository.insertOrReplace(it) }
                    val as_ = syncActivityUseCase.execute(acceptedActivities)
                    // rawPayloads is empty when packets were persisted on arrival (Fix 18);
                    // non-empty only on the legacy path where BleEngine held them in memory.
                    rawDeviceDataRepository.insertAll(result.rawPayloads, syncSessionId)
                    Pair(rs, as_)
                }

            // g. Compute final counts and status (battery readings are not counted — they go to device metadata).
            val healthReadingsTotal = result.metricReadings.count { it.metricType != MetricType.BATTERY }
            val readingsAccepted = acceptedReadings.size
            val readingsRejected = healthReadingsTotal - readingsAccepted
            val sessionsAccepted = acceptedSessions.size
            val sessionsInserted = mergedSessions.size
            val sessionsRejected = result.sleepSessions.size - acceptedSessions.size
            val activitiesAccepted = acceptedActivities.size
            val activitiesInserted = activitiesAccepted - activitiesSkipped
            val activitiesRejected = result.activities.size - activitiesAccepted
            val totalAccepted = readingsAccepted + sessionsAccepted + activitiesAccepted
            val totalRejected = readingsRejected + sessionsRejected + activitiesRejected

            if (deviceInsert.accumulatorGuarded > 0) {
                Timber.w("Sync: %d accumulator value(s) guarded (incoming < stored)", deviceInsert.accumulatorGuarded)
            }

            val finalStatus = when {
                totalRejected == 0 -> SyncStatus.SUCCESS
                totalAccepted == 0 -> SyncStatus.FAILED
                else -> SyncStatus.PARTIAL
            }

            val genuinelyNew = deviceInsert.newRecordsInserted + deviceInsert.accumulatorUpdates +
                sessionsInserted + activitiesInserted

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
                    syncedBeforeQuiescence = result.syncedBeforeQuiescence,
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
                readingResults.filterIsInstance<ValidationResult.Rejected<MetricReading>>()
                    .forEach { add(it.reason) }
                sessionResults.filterIsInstance<ValidationResult.Rejected<SleepSession>>()
                    .forEach { add(it.reason) }
                activityResults.filterIsInstance<ValidationResult.Rejected<Activity>>()
                    .forEach { add(it.reason) }
            }

            Timber.i(
                "SyncSummary: newInserted=%d accumulatorUpdates=%d accumulatorNoChange=%d " +
                "accumulatorGuarded=%d readingsSkipped=%d | sessions inserted=%d | " +
                "activities inserted=%d skipped=%d | status=%s | " +
                "packetsReceived=%d syncedBeforeQuiescence=%b",
                deviceInsert.newRecordsInserted, deviceInsert.accumulatorUpdates,
                deviceInsert.accumulatorNoChange, deviceInsert.accumulatorGuarded,
                deviceInsert.readingsSkipped,
                sessionsInserted,
                activitiesInserted, activitiesSkipped,
                finalStatus,
                result.packetsReceived, result.syncedBeforeQuiescence,
            )

            return SyncSummary(
                newRecordsInserted = deviceInsert.newRecordsInserted,
                accumulatorUpdates = deviceInsert.accumulatorUpdates,
                accumulatorNoChange = deviceInsert.accumulatorNoChange,
                accumulatorGuarded = deviceInsert.accumulatorGuarded,
                readingsSkipped = deviceInsert.readingsSkipped,
                sessionsInserted = sessionsInserted,
                activitiesInserted = activitiesInserted,
                activitiesSkipped = activitiesSkipped,
                rejectionReasons = rejectionReasons,
                finalStatus = finalStatus,
                packetsReceived = result.packetsReceived,
                syncedBeforeQuiescence = result.syncedBeforeQuiescence,
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

    /**
     * Replays raw_device_data for [sessionId] through the current WASM driver and imports
     * the results, completing an interrupted sync without reconnecting to the device.
     */
    suspend fun processFromRaw(sessionId: Long): SyncSummary {
        val session = syncSessionRepository.getById(sessionId)
            ?: error("SyncSession $sessionId not found")
        val device = deviceRepository.getDeviceById(session.deviceId)
            ?: error("Device ${session.deviceId} not found for session $sessionId")
        val manifest = driverRegistry.allDrivers().find { it.id == session.driverId }
            ?: error("Driver '${session.driverId}' not loaded — cannot recover session $sessionId")

        val rawPayloads = rawDeviceDataRepository.getForSession(sessionId)
        Timber.i("processFromRaw: replaying ${rawPayloads.size} packets for session $sessionId via driver ${session.driverId}")

        try {
            val allMetrics = mutableListOf<MetricReading>()
            val allSleep = mutableListOf<SleepSession>()
            val allActivities = mutableListOf<Activity>()
            for (payload in rawPayloads) {
                allMetrics += driverRegistry.parseMetrics(manifest, payload.characteristicUuid, payload.payload)
                driverRegistry.parseSleep(manifest, payload.characteristicUuid, payload.payload)
                    ?.let { allSleep += it }
                driverRegistry.parseActivity(manifest, payload.characteristicUuid, payload.payload)
                    ?.let { allActivities += it }
            }

            val readingResults = validator.validateReadings(allMetrics)
            val sessionResults = validator.validateSessions(allSleep)
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

            val mergedSessions = buildMergedSessions(acceptedSessions)
            val (routeResult, activitiesSkipped) =
                appDatabase.withTransaction {
                    val rr = metricRouter.routeAll(acceptedReadings)
                    mergedSessions.forEach { sleepRepository.insertOrReplace(it) }
                    Pair(rr, syncActivityUseCase.execute(acceptedActivities))
                }

            val healthReadingsTotal = allMetrics.count { it.metricType != MetricType.BATTERY }
            val readingsAccepted = acceptedReadings.size
            val readingsRejected = healthReadingsTotal - readingsAccepted
            val sessionsAccepted = acceptedSessions.size
            val sessionsInserted = mergedSessions.size
            val sessionsRejected = allSleep.size - acceptedSessions.size
            val activitiesAccepted = acceptedActivities.size
            val activitiesInserted = activitiesAccepted - activitiesSkipped
            val activitiesRejected = allActivities.size - activitiesAccepted
            val totalAccepted = readingsAccepted + sessionsAccepted + activitiesAccepted
            val totalRejected = readingsRejected + sessionsRejected + activitiesRejected

            val finalStatus = when {
                totalRejected == 0 -> SyncStatus.SUCCESS
                totalAccepted == 0 -> SyncStatus.FAILED
                else -> SyncStatus.PARTIAL
            }

            val genuinelyNew = routeResult.newRecordsInserted + routeResult.accumulatorUpdates +
                sessionsInserted + activitiesInserted

            val syncEndedAt = Instant.now()
            sleepStagePromoter.promote(
                driverId = session.driverId,
                syncWindowStartMs = session.startedAt.toEpochMilli(),
                syncWindowEndMs = syncEndedAt.toEpochMilli(),
            )
            syncSessionRepository.update(
                SyncSession(
                    id = sessionId,
                    deviceId = device.id,
                    driverId = session.driverId,
                    startedAt = session.startedAt,
                    endedAt = syncEndedAt,
                    status = finalStatus,
                    recordsImported = genuinelyNew,
                    packetsReceived = session.packetsReceived,
                    syncedBeforeQuiescence = session.syncedBeforeQuiescence,
                )
            )

            schedulePrune()

            deviceRepository.updateLastSync(device.bleAddress, syncEndedAt.toEpochMilli())
            allMetrics.filter { it.metricType == MetricType.BATTERY }.lastOrNull()
                ?.let { deviceRepository.updateLastBatteryPct(device.bleAddress, it.value.toInt()) }

            val rejectionReasons = buildList {
                readingResults.filterIsInstance<ValidationResult.Rejected<MetricReading>>()
                    .forEach { add(it.reason) }
                sessionResults.filterIsInstance<ValidationResult.Rejected<SleepSession>>()
                    .forEach { add(it.reason) }
                activityResults.filterIsInstance<ValidationResult.Rejected<Activity>>()
                    .forEach { add(it.reason) }
            }

            Timber.i(
                "processFromRaw session=$sessionId: newInserted=%d accumulatorUpdates=%d " +
                "readingsSkipped=%d readingsRejected=%d | sessions inserted=%d | " +
                "activities inserted=%d | status=%s",
                routeResult.newRecordsInserted, routeResult.accumulatorUpdates,
                readingsAccepted - routeResult.newRecordsInserted - routeResult.accumulatorUpdates,
                readingsRejected, sessionsInserted, activitiesInserted, finalStatus,
            )

            return SyncSummary(
                newRecordsInserted = routeResult.newRecordsInserted,
                accumulatorUpdates = routeResult.accumulatorUpdates,
                accumulatorNoChange = 0,
                accumulatorGuarded = 0,
                readingsSkipped = readingsAccepted - routeResult.newRecordsInserted - routeResult.accumulatorUpdates,
                sessionsInserted = sessionsInserted,
                activitiesInserted = activitiesInserted,
                activitiesSkipped = activitiesSkipped,
                rejectionReasons = rejectionReasons,
                finalStatus = finalStatus,
                packetsReceived = session.packetsReceived,
                syncedBeforeQuiescence = session.syncedBeforeQuiescence,
            )

        } catch (e: Exception) {
            runCatching {
                syncSessionRepository.update(
                    SyncSession(
                        id = sessionId,
                        deviceId = device.id,
                        driverId = session.driverId,
                        startedAt = session.startedAt,
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
