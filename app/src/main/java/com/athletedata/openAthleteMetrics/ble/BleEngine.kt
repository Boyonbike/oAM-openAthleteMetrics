package com.athletedata.openAthleteMetrics.ble

import android.content.Context
import com.athletedata.openAthleteMetrics.ble.driver.DriverRegistry
import com.athletedata.openAthleteMetrics.ble.driver.SyncCommand
import com.athletedata.openAthleteMetrics.ble.driver.WasmDriverManifest
import com.athletedata.openAthleteMetrics.ble.sync.DeviceSyncProcessor
import com.athletedata.openAthleteMetrics.ble.sync.SyncSummary
import com.athletedata.openAthleteMetrics.data.model.Activity
import com.athletedata.openAthleteMetrics.data.model.DriverSyncResult
import com.athletedata.openAthleteMetrics.data.model.MetricReading
import com.athletedata.openAthleteMetrics.data.model.RawPayload
import com.athletedata.openAthleteMetrics.data.model.SleepSession
import com.athletedata.openAthleteMetrics.data.repository.DeviceRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BleEngine @Inject constructor(
    @ApplicationContext val context: Context,
    private val driverRegistry: DriverRegistry,
    private val syncProcessor: DeviceSyncProcessor,
    private val deviceRepository: DeviceRepository,
) {
    private var activeManifest: WasmDriverManifest? = null
    private var activeDeviceAddress: String? = null
    private var syncStartedAt: Instant = Instant.now()

    private val pendingMetrics = mutableListOf<MetricReading>()
    private val pendingSleep = mutableListOf<SleepSession>()
    private val pendingActivities = mutableListOf<Activity>()
    private val pendingRaw = mutableListOf<RawPayload>()

    fun onScanResult(deviceName: String?, deviceAddress: String, serviceUuids: List<String>) {
        val (manifest, confidence) = driverRegistry.resolve(deviceName, serviceUuids) ?: return
        Timber.d("BleEngine: matched driver ${manifest.id} for $deviceName ($confidence)")
        activeManifest = manifest
        activeDeviceAddress = deviceAddress
        syncStartedAt = Instant.now()
    }

    fun buildSyncCommands(): List<BleCommand> =
        activeManifest?.syncCommands.orEmpty().map { cmd ->
            when (cmd) {
                is SyncCommand.Write -> BleCommand.Write(cmd.characteristic, cmd.bytes)
                is SyncCommand.EnableNotify -> BleCommand.EnableNotify(cmd.characteristic)
                is SyncCommand.Delay -> BleCommand.Delay(cmd.millis)
            }
        }

    fun handleNotification(characteristicUuid: String, bytes: ByteArray) {
        val manifest = activeManifest ?: return
        pendingMetrics += driverRegistry.parseMetrics(manifest, characteristicUuid, bytes)
        driverRegistry.parseSleep(manifest, characteristicUuid, bytes)?.let { pendingSleep += it }
        driverRegistry.parseActivity(manifest, characteristicUuid, bytes)?.let { pendingActivities += it }
        pendingRaw += RawPayload(
            characteristicUuid = characteristicUuid,
            payload = bytes,
            receivedAt = Instant.now(),
        )
    }

    suspend fun triggerSync(): SyncSummary? {
        val manifest = activeManifest ?: return null
        val address = activeDeviceAddress ?: return null
        val result = DriverSyncResult(
            deviceId = address,
            driverId = manifest.id,
            syncStartedAt = syncStartedAt,
            syncEndedAt = Instant.now(),
            metricReadings = pendingMetrics.toList(),
            sleepSessions = pendingSleep.toList(),
            activities = pendingActivities.toList(),
            rawPayloads = pendingRaw.toList(),
        )
        pendingMetrics.clear()
        pendingSleep.clear()
        pendingActivities.clear()
        pendingRaw.clear()
        return syncProcessor.process(result)
    }
}
