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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    // TODO: thread-safety — mutate only on Main when real BLE callbacks are wired
    private val pendingMetrics = mutableListOf<MetricReading>()
    private val pendingSleep = mutableListOf<SleepSession>()
    private val pendingActivities = mutableListOf<Activity>()
    private val pendingRaw = mutableListOf<RawPayload>()

    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Idle)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    fun startScan() {
        val current = _connectionState.value
        if (current !is BleConnectionState.Idle && current !is BleConnectionState.Error) return
        _connectionState.value = BleConnectionState.Scanning
        // TODO: Start Android BluetoothLeScanner here when hardware integration is added
    }

    fun disconnect() {
        val address = activeDeviceAddress ?: return
        _connectionState.value = BleConnectionState.Disconnected(address, null)
        activeManifest = null
        activeDeviceAddress = null
        pendingMetrics.clear()
        pendingSleep.clear()
        pendingActivities.clear()
        pendingRaw.clear()
    }

    fun acknowledgeSyncComplete() {
        if (_connectionState.value !is BleConnectionState.SyncComplete) return
        val address = activeDeviceAddress ?: return
        val driverName = activeManifest?.displayName ?: return
        _connectionState.value = BleConnectionState.Connected(address, driverName)
    }

    fun resetToIdle() {
        if (_connectionState.value is BleConnectionState.Disconnected) {
            _connectionState.value = BleConnectionState.Idle
        }
    }

    fun onScanResult(deviceName: String?, deviceAddress: String, serviceUuids: List<String>) {
        val (manifest, confidence) = driverRegistry.resolve(deviceName, serviceUuids) ?: return
        Timber.d("BleEngine: matched driver ${manifest.id} for $deviceName ($confidence)")
        activeManifest = manifest
        activeDeviceAddress = deviceAddress
        syncStartedAt = Instant.now()
        _connectionState.value = BleConnectionState.Connecting(deviceAddress)
        // TODO: initiate GATT connect; advance to Connected in the GATT callback
        _connectionState.value = BleConnectionState.Connected(deviceAddress, manifest.displayName)
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
        if (_connectionState.value !is BleConnectionState.Connected) return null
        val manifest = activeManifest ?: return null
        val address = activeDeviceAddress ?: return null
        _connectionState.value = BleConnectionState.Syncing(address, 0f)
        return try {
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
            val summary = syncProcessor.process(result)
            _connectionState.value = BleConnectionState.SyncComplete(summary)
            summary
        } catch (e: Exception) {
            _connectionState.value = BleConnectionState.Error(e.message ?: "Sync failed")
            null
        }
    }
}
