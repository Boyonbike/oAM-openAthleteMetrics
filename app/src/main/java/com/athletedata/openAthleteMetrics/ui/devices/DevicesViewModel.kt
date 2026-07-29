package com.athletedata.openAthleteMetrics.ui.devices

import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.athletedata.openAthleteMetrics.ble.BleConnectionState
import com.athletedata.openAthleteMetrics.ble.BleEngine
import com.athletedata.openAthleteMetrics.ble.DiscoveredCandidate
import com.athletedata.openAthleteMetrics.ble.companion.CompanionDeviceAssociator
import com.athletedata.openAthleteMetrics.ble.driver.DriverRegistry
import com.athletedata.openAthleteMetrics.ble.driver.DriverStorage
import com.athletedata.openAthleteMetrics.ble.driver.WasmDriverManifest
import com.athletedata.openAthleteMetrics.ble.sync.DeviceReprocessor
import com.athletedata.openAthleteMetrics.ble.sync.MultiDeviceSyncOrchestrator
import com.athletedata.openAthleteMetrics.data.model.Device
import com.athletedata.openAthleteMetrics.data.repository.DeviceRepository
import com.athletedata.openAthleteMetrics.data.repository.SettingsRepository
import com.athletedata.openAthleteMetrics.data.repository.SyncSessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject

enum class DevicesTab { DEVICE, DRIVER }

// ADDED: interrupted-sync-message
data class SyncInterruptedState(val show: Boolean)

sealed class ReprocessState {
    object Idle : ReprocessState()
    data class Running(val progress: Float) : ReprocessState()
    data class Done(val recordsReplaced: Int, val datesAffected: Int) : ReprocessState()
    data class Failed(val message: String) : ReprocessState()
}

sealed class DriverEvent {
    data class ValidationError(val errors: List<String>) : DriverEvent()
    data class Error(val message: String) : DriverEvent()
    // CHANGED: non-blocking cross-driver signature collision warning (see DriverRegistry.register)
    data class CollisionWarning(val warnings: List<String>) : DriverEvent()
}

@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val driverStorage: DriverStorage,
    private val driverRegistry: DriverRegistry,
    private val bleEngine: BleEngine,
    private val multiDeviceSyncOrchestrator: MultiDeviceSyncOrchestrator,
    private val deviceReprocessor: DeviceReprocessor,
    private val syncSessionRepository: SyncSessionRepository,
    private val settingsRepository: SettingsRepository,
    private val companionDeviceAssociator: CompanionDeviceAssociator,
) : ViewModel() {

    val devices: StateFlow<List<Device>> = deviceRepository.getAllDevices()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val _selectedTab = MutableStateFlow(DevicesTab.DRIVER)
    val selectedTab: StateFlow<DevicesTab> = _selectedTab.asStateFlow()

    private val _drivers = MutableStateFlow(driverRegistry.allDrivers())
    val drivers: StateFlow<List<WasmDriverManifest>> = _drivers.asStateFlow()

    private val _driverEvents = Channel<DriverEvent>(Channel.BUFFERED)
    val driverEvents: Flow<DriverEvent> = _driverEvents.receiveAsFlow()

    private val _snackbarEvents = Channel<String>(Channel.BUFFERED)
    val snackbarEvents: Flow<String> = _snackbarEvents.receiveAsFlow()

    // One-shot CDM consent requests for DevicesScreen to launch via
    // ActivityResultContracts.StartIntentSenderForResult - mirrors _snackbarEvents' shape.
    private val _associationRequests = Channel<IntentSender>(Channel.BUFFERED)
    val associationRequests: Flow<IntentSender> = _associationRequests.receiveAsFlow()

    val connectionState: StateFlow<BleConnectionState> = bleEngine.connectionState
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = BleConnectionState.Idle,
        )

    val discoveredCandidates = bleEngine.discoveredCandidates

    private val _reprocessState = MutableStateFlow<ReprocessState>(ReprocessState.Idle)
    val reprocessState: StateFlow<ReprocessState> = _reprocessState.asStateFlow()

    private val _reprocessingDeviceId = MutableStateFlow<Long?>(null)
    val reprocessingDeviceId: StateFlow<Long?> = _reprocessingDeviceId.asStateFlow()

    // REMOVED: interrupted-sync-recovery
    // ADDED: interrupted-sync-message
    private val _syncInterrupted = MutableStateFlow(SyncInterruptedState(show = false))
    val syncInterrupted: StateFlow<SyncInterruptedState> = _syncInterrupted.asStateFlow()

    val restarConfirmDismissed: StateFlow<Boolean> = settingsRepository.getRestarConfirmDismissed()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )

    init {
        // CHANGE 1: default to Device tab if either drivers or devices exist
        viewModelScope.launch {
            val hasDrivers = driverRegistry.allDrivers().isNotEmpty()
            val hasDevices = deviceRepository.getAllDevices().first().isNotEmpty()
            if (hasDrivers || hasDevices) {
                _selectedTab.value = DevicesTab.DEVICE
            }
        }

        // ADDED: interrupted-sync-message — detect interrupted syncs on startup, clear them, and show banner
        viewModelScope.launch(Dispatchers.IO) {
            val since = Instant.now().minus(24, ChronoUnit.HOURS)
            val interrupted = syncSessionRepository.getRecentInProgress(since)
            if (interrupted.isNotEmpty()) {
                syncSessionRepository.markOldInProgressAsFailed(Instant.now())
                _syncInterrupted.value = SyncInterruptedState(show = true)
            }
        }
    }

    fun onAddDeviceTapped() {
        if (multiDeviceSyncOrchestrator.isRunningNow) {
            viewModelScope.launch { _snackbarEvents.send("Sync in progress") }
            return
        }
        if (!bleEngine.startScan()) {
            viewModelScope.launch { _snackbarEvents.send("Sync in progress") }
        }
    }

    fun onCandidateSelected(candidate: DiscoveredCandidate) {
        if (multiDeviceSyncOrchestrator.isRunningNow) {
            viewModelScope.launch { _snackbarEvents.send("Sync in progress") }
            return
        }
        // Pairing must not block on CDM consent, so once the connect is accepted the
        // association request runs alongside it and only affects whether this device
        // later gets a presence-triggered background wake (see onAssociationResult).
        if (!bleEngine.connectToCandidate(candidate)) {
            viewModelScope.launch { _snackbarEvents.send("Sync in progress") }
            return
        }
        viewModelScope.launch {
            val chooserLauncher = companionDeviceAssociator.requestAssociation(candidate.address, candidate.deviceName)
            if (chooserLauncher != null) {
                _associationRequests.send(chooserLauncher)
            }
        }
    }

    /**
     * Called by DevicesScreen once the user has resolved the CDM consent dialog launched from
     * [associationRequests]. A decline (granted = false) is not an error - the device simply
     * falls back to periodic-only background sync via BackgroundSyncWorker.
     */
    fun onAssociationResult(bleAddress: String, granted: Boolean) {
        viewModelScope.launch {
            deviceRepository.setCdmAssociated(bleAddress, granted)
            if (granted) {
                companionDeviceAssociator.startObservingPresence(bleAddress)
            }
        }
    }

    fun onDeviceCellTapped(device: Device) {
        if (multiDeviceSyncOrchestrator.isRunningNow) {
            viewModelScope.launch { _snackbarEvents.send("Sync in progress") }
            return
        }
        val manifest = driverRegistry.allDrivers().find { it.id == device.driverId }
        if (manifest == null) {
            viewModelScope.launch {
                _snackbarEvents.send("Driver '${device.driverId}' not loaded. Add the driver first.")
            }
            return
        }
        if (!bleEngine.connectToDevice(device.bleAddress, manifest)) {
            viewModelScope.launch { _snackbarEvents.send("Sync in progress") }
        }
    }

    fun onRemoveDeviceTapped(device: Device) {
        val current = connectionState.value
        val isThisDevice = when (current) {
            is BleConnectionState.Connected  -> current.deviceAddress == device.bleAddress
            is BleConnectionState.Syncing    -> current.deviceAddress == device.bleAddress
            is BleConnectionState.Parsing    -> current.deviceAddress == device.bleAddress
            is BleConnectionState.Connecting -> current.deviceAddress == device.bleAddress
            else -> false
        }
        if (isThisDevice) bleEngine.disconnect()
        viewModelScope.launch { deviceRepository.delete(device) }
    }

    fun onStarTapped(device: Device) {
        if (device.isPrimary) return
        viewModelScope.launch { deviceRepository.setPrimary(device.id) }
    }

    fun onAutoSyncToggled(device: Device, enabled: Boolean) {
        viewModelScope.launch { deviceRepository.setAutoSync(device.id, enabled) }
    }

    fun onRestarConfirmDismissedChanged(dismissed: Boolean) {
        viewModelScope.launch { settingsRepository.setRestarConfirmDismissed(dismissed) }
    }

    fun onSyncTapped() {
        if (connectionState.value !is BleConnectionState.Connected) return
        bleEngine.startSync()
    }

    fun onDisconnectTapped() { bleEngine.disconnect() }

    fun onSyncAcknowledged() { bleEngine.acknowledgeSyncComplete() }

    fun onDisconnectDismissed() { bleEngine.resetToIdle() }

    fun onScanCancelled() { bleEngine.cancelScan() }

    fun onCancelSync() { bleEngine.cancelSync() }

    // REMOVED: interrupted-sync-recovery — onRecoverSessionTapped() deleted
    // ADDED: interrupted-sync-message
    fun onSyncInterruptedDismissed() {
        _syncInterrupted.value = SyncInterruptedState(show = false)
    }

    fun onReprocessConfirmed(device: Device) {
        if (connectionState.value !is BleConnectionState.Idle) {
            viewModelScope.launch {
                _snackbarEvents.send("Cannot reprocess while a BLE operation is active.")
            }
            return
        }
        if (_reprocessState.value is ReprocessState.Running) return

        _reprocessingDeviceId.value = device.id
        _reprocessState.value = ReprocessState.Running(0f)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val since = Instant.now().minus(7, ChronoUnit.DAYS)
                val summary = deviceReprocessor.reprocess(
                    device = device,
                    since = since,
                    onProgress = { _reprocessState.value = ReprocessState.Running(it) },
                )
                if (summary.error != null) {
                    _reprocessState.value = ReprocessState.Failed(summary.error)
                    _snackbarEvents.send("Reprocess failed: ${summary.error}")
                } else {
                    _reprocessState.value = ReprocessState.Done(
                        recordsReplaced = summary.recordsReplaced,
                        datesAffected = summary.datesAffected.size,
                    )
                    _snackbarEvents.send(
                        "Reprocessed ${summary.recordsReplaced} records across ${summary.datesAffected.size} days"
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Reprocessing failed for device ${device.id}")
                _reprocessState.value = ReprocessState.Failed(e.message ?: "Unknown error")
                _snackbarEvents.send("Reprocess failed: ${e.message}")
            } finally {
                _reprocessingDeviceId.value = null
                delay(3.seconds)
                if (_reprocessState.value !is ReprocessState.Running) {
                    _reprocessState.value = ReprocessState.Idle
                }
            }
        }
    }

    fun selectTab(tab: DevicesTab) {
        _selectedTab.value = tab
    }

    fun onDriverFileSelected(uri: Uri) {
        viewModelScope.launch {
            when (val result = driverStorage.saveDriver(uri)) {
                is DriverStorage.DriverSaveResult.Success -> {
                    // CHANGED: surface cross-driver collision warnings instead of discarding them
                    val warnings = driverRegistry.register(result.manifest)
                    refreshDrivers()
                    if (warnings.isNotEmpty()) _driverEvents.send(DriverEvent.CollisionWarning(warnings))
                }
                is DriverStorage.DriverSaveResult.Invalid ->
                    _driverEvents.send(DriverEvent.ValidationError(result.errors))
                is DriverStorage.DriverSaveResult.Error ->
                    _driverEvents.send(DriverEvent.Error(result.message))
            }
        }
    }

    fun deleteDriver(driverId: String) {
        viewModelScope.launch {
            driverRegistry.unregister(driverId)
            refreshDrivers()
            withContext(Dispatchers.IO) { driverStorage.deleteDriver(driverId) }
        }
    }

    private fun refreshDrivers() {
        _drivers.value = driverRegistry.allDrivers()
    }
}
