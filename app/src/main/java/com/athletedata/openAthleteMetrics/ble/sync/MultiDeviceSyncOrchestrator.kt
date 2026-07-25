package com.athletedata.openAthleteMetrics.ble.sync

import com.athletedata.openAthleteMetrics.ble.BleConnectionState
import com.athletedata.openAthleteMetrics.ble.BleEngine
import com.athletedata.openAthleteMetrics.ble.driver.DriverRegistry
import com.athletedata.openAthleteMetrics.data.model.Device
import com.athletedata.openAthleteMetrics.data.repository.DeviceRepository
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

private const val TAG = "data-pathway-tracker" // DPT

/**
 * Drives a sequential (one-at-a-time) sync across every auto-sync-enabled device, reusing
 * BleEngine's single-connection design as-is: connect -> observe connectionState until a
 * terminal outcome -> disconnect/acknowledge -> only then advance. BleEngine.connectToDevice()
 * has no reentrancy gate of its own (unlike startScan()), so this class owns the only gate
 * that keeps a second connect from clobbering activeGatt/activeManifest/activeDeviceAddress
 * mid-cycle.
 */
@Singleton
class MultiDeviceSyncOrchestrator @Inject constructor(
    private val bleEngine: BleEngine,
    private val deviceRepository: DeviceRepository,
    private val driverRegistry: DriverRegistry,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val isRunning = AtomicBoolean(false)

    private val _progress = MutableStateFlow<SyncProgress?>(null)
    val progress: StateFlow<SyncProgress?> = _progress.asStateFlow()

    // Fire-and-forget entry point (mirrors BleEngine.startSync()/triggerSync()) for callers
    // (MainActivity) that shouldn't tie the run's lifetime to their own scope.
    fun startAutoSync() {
        if (!isRunning.compareAndSet(false, true)) {
            Timber.tag(TAG).i("MultiDeviceSyncOrchestrator: run already in progress, ignoring request")
            return
        }
        scope.launch {
            try {
                runSequentialSync()
            } finally {
                _progress.value = null
                isRunning.set(false)
            }
        }
    }

    suspend fun runSequentialSync() {
        if (bleEngine.connectionState.value !is BleConnectionState.Idle) {
            Timber.tag(TAG).i("MultiDeviceSyncOrchestrator: engine busy (%s), skipping auto-sync run", bleEngine.connectionState.value)
            return
        }
        val devices = deviceRepository.getAutoSyncEnabledOrdered()
        devices.forEachIndexed { index, device ->
            // Deliberately not "must be exactly Idle" here: finishDevice() can leave the
            // engine parked at a dead-end Disconnected (its own no-op-cleanup case, see
            // finishDevice) rather than Idle, and that's fine to plow through — the next
            // connectToDevice() resets engine state unconditionally regardless. What must
            // actually abort the run is a genuinely active connection — i.e. some OTHER
            // caller (a manual tap in DevicesViewModel) grabbing the engine mid-run, which
            // this class has no way to gate against beyond detecting it here.
            if (isEngineActive(bleEngine.connectionState.value)) {
                Timber.tag(TAG).w("MultiDeviceSyncOrchestrator: engine became active before %s, aborting remaining run", device.displayName)
                return
            }
            _progress.value = SyncProgress(device.displayName, index + 1, devices.size)
            syncOneDevice(device)
        }
    }

    private fun isEngineActive(state: BleConnectionState): Boolean = when (state) {
        is BleConnectionState.Connecting,
        is BleConnectionState.Connected,
        is BleConnectionState.Syncing,
        is BleConnectionState.Parsing,
        is BleConnectionState.SyncComplete -> true
        else -> false
    }

    private suspend fun syncOneDevice(device: Device) {
        val manifest = driverRegistry.allDrivers().find { it.id == device.driverId }
        if (manifest == null) {
            Timber.tag(TAG).w("MultiDeviceSyncOrchestrator: no driver installed for %s (%s), skipping", device.displayName, device.driverId)
            return
        }

        bleEngine.connectToDevice(device.bleAddress, manifest)

        val outcome = withTimeoutOrNull(DEVICE_SYNC_TIMEOUT_MS) {
            bleEngine.connectionState.filter(::isTerminal).first()
        }
        when (outcome) {
            null -> Timber.tag(TAG).w("MultiDeviceSyncOrchestrator: timed out syncing %s", device.displayName)
            is BleConnectionState.SyncComplete -> Timber.tag(TAG).i("MultiDeviceSyncOrchestrator: synced %s", device.displayName)
            is BleConnectionState.Connected -> Timber.tag(TAG).i("MultiDeviceSyncOrchestrator: synced %s (quiescent)", device.displayName)
            else -> Timber.tag(TAG).w("MultiDeviceSyncOrchestrator: failed syncing %s: %s", device.displayName, outcome)
        }
        finishDevice(outcome)
    }

    private fun isTerminal(state: BleConnectionState): Boolean = when (state) {
        is BleConnectionState.SyncComplete -> true
        is BleConnectionState.Connected -> state.isQuiescent
        is BleConnectionState.Error,
        is BleConnectionState.GattCacheError,
        is BleConnectionState.Disconnected -> true
        else -> false
    }

    // disconnect()/acknowledgeSyncComplete() can be a genuine no-op (e.g. after
    // BleEngine.scheduleRetry() exhausts MAX_RETRIES, which already nulls
    // activeDeviceAddress itself before this runs) — in that case there is nothing to wait
    // for, and connectToDevice() will reset engine state unconditionally on the next device
    // regardless. Only wait when the cleanup call actually changed something.
    private suspend fun finishDevice(outcome: BleConnectionState?) {
        val beforeCleanup = bleEngine.connectionState.value
        if (outcome is BleConnectionState.SyncComplete) {
            bleEngine.acknowledgeSyncComplete()
        } else {
            bleEngine.disconnect()
        }
        if (bleEngine.connectionState.value == beforeCleanup) return

        val settled = withTimeoutOrNull(SETTLE_TIMEOUT_MS) {
            bleEngine.connectionState.filter { it is BleConnectionState.Idle }.first()
        }
        if (settled == null) {
            Timber.tag(TAG).e("MultiDeviceSyncOrchestrator: engine did not settle to Idle within %dms — possible orphaned GATT, proceeding anyway", SETTLE_TIMEOUT_MS)
        }
    }

    companion object {
        // 5 min backstop against a genuinely wedged BLE stack (e.g. connectGatt() never
        // invoking any callback at all). BleEngine's own timeouts (15s silent-sync, 5s
        // disconnect, retry backoff) resolve stuck states well before this in the common cases.
        private const val DEVICE_SYNC_TIMEOUT_MS = 300_000L
        private const val SETTLE_TIMEOUT_MS = 6_000L // DISCONNECT_TIMEOUT_MS (5s) + buffer
    }
}
