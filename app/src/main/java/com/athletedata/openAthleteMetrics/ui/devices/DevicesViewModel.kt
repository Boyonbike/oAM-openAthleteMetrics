package com.athletedata.openAthleteMetrics.ui.devices

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.athletedata.openAthleteMetrics.ble.BleConnectionState
import com.athletedata.openAthleteMetrics.ble.BleEngine
import com.athletedata.openAthleteMetrics.ble.driver.DriverRegistry
import com.athletedata.openAthleteMetrics.ble.driver.DriverStorage
import com.athletedata.openAthleteMetrics.ble.driver.WasmDriverManifest
import com.athletedata.openAthleteMetrics.data.model.Device
import com.athletedata.openAthleteMetrics.data.repository.DeviceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
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
import javax.inject.Inject

enum class DevicesTab { DEVICE, DRIVER }

sealed class DriverEvent {
    data class ValidationError(val errors: List<String>) : DriverEvent()
    data class Error(val message: String) : DriverEvent()
}

@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val driverStorage: DriverStorage,
    private val driverRegistry: DriverRegistry,
    private val bleEngine: BleEngine,
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

    val connectionState: StateFlow<BleConnectionState> = bleEngine.connectionState
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = BleConnectionState.Idle,
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

    }

    fun onAddDeviceTapped() {
        val current = connectionState.value
        if (current is BleConnectionState.Idle || current is BleConnectionState.Error) {
            bleEngine.startScan()
        }
    }

    fun onDeviceCellTapped(device: Device) {
        val current = connectionState.value
        if (current !is BleConnectionState.Idle && current !is BleConnectionState.Error) return
        val manifest = driverRegistry.allDrivers().find { it.id == device.driverId }
        if (manifest == null) {
            viewModelScope.launch {
                _snackbarEvents.send("Driver '${device.driverId}' not loaded. Add the driver first.")
            }
            return
        }
        bleEngine.connectToDevice(device.bleAddress, manifest)
    }

    fun onRemoveDeviceTapped(device: Device) {
        val current = connectionState.value
        val isThisDevice = when (current) {
            is BleConnectionState.Connected  -> current.deviceAddress == device.bleAddress
            is BleConnectionState.Syncing    -> current.deviceAddress == device.bleAddress
            is BleConnectionState.Connecting -> current.deviceAddress == device.bleAddress
            else -> false
        }
        if (isThisDevice) bleEngine.disconnect()
        viewModelScope.launch { deviceRepository.delete(device) }
    }

    fun onSyncTapped() {
        if (connectionState.value !is BleConnectionState.Connected) return
        viewModelScope.launch { bleEngine.triggerSync() }
    }

    fun onDisconnectTapped() { bleEngine.disconnect() }

    fun onSyncAcknowledged() { bleEngine.acknowledgeSyncComplete() }

    fun onDisconnectDismissed() { bleEngine.resetToIdle() }

    fun selectTab(tab: DevicesTab) {
        _selectedTab.value = tab
    }

    fun onDriverFileSelected(uri: Uri) {
        viewModelScope.launch {
            when (val result = driverStorage.saveDriver(uri)) {
                is DriverStorage.DriverSaveResult.Success -> {
                    driverRegistry.register(result.manifest)
                    refreshDrivers()
                }
                is DriverStorage.DriverSaveResult.Invalid ->
                    _driverEvents.send(DriverEvent.ValidationError(result.errors))
                is DriverStorage.DriverSaveResult.Error ->
                    _driverEvents.send(DriverEvent.Error(result.message))
            }
        }
    }

    fun deleteDriver(driverId: String) {
        driverRegistry.unregister(driverId)
        refreshDrivers()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { driverStorage.deleteDriver(driverId) }
        }
    }

    private fun refreshDrivers() {
        _drivers.value = driverRegistry.allDrivers()
    }
}
