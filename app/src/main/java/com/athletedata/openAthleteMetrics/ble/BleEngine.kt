package com.athletedata.openAthleteMetrics.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import androidx.annotation.RequiresApi
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BleEngine @Inject constructor(
    @ApplicationContext val context: Context,
    private val driverRegistry: DriverRegistry,
    private val syncProcessor: DeviceSyncProcessor,
    private val deviceRepository: DeviceRepository,
) {
    companion object {
        private const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
        private const val MTU_REQUEST = 512
        private const val SCAN_TIMEOUT_MS = 30_000L
        private const val MAX_RETRIES = 3
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var activeManifest: WasmDriverManifest? = null
    private var activeDeviceAddress: String? = null
    private var syncStartedAt: Instant = Instant.now()

    private val pendingMetrics = mutableListOf<MetricReading>()
    private val pendingSleep = mutableListOf<SleepSession>()
    private val pendingActivities = mutableListOf<Activity>()
    private val pendingRaw = mutableListOf<RawPayload>()

    private var activeGatt: BluetoothGatt? = null
    private var bleScanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var scanTimeoutJob: Job? = null
    private var retryCount = 0
    private var retryJob: Job? = null
    private val notifySetupQueue = ArrayDeque<String>()
    private var commandIndex = 0
    private var inSyncCommandNotify = false
    private var userDisconnecting = false

    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Idle)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun startScan() {
        val current = _connectionState.value
        if (current !is BleConnectionState.Idle && current !is BleConnectionState.Error) return

        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            _connectionState.value = BleConnectionState.Error("Bluetooth is disabled")
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            _connectionState.value = BleConnectionState.Error("Bluetooth scanner unavailable")
            return
        }
        bleScanner = scanner

        val uuidFilters = driverRegistry.allDrivers()
            .mapNotNull { it.ble.matchByServiceUuid }
            .distinct()
            .map { uuid ->
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid.fromString(uuid))
                    .build()
            }
        val nameFilters = driverRegistry.allDrivers()
            .mapNotNull { it.ble.matchByName }
            .distinct()
            .map { name ->
                ScanFilter.Builder()
                    .setDeviceName(name)
                    .build()
            }
        val filters = uuidFilters + nameFilters

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        _connectionState.value = BleConnectionState.Scanning
        scanner.startScan(filters.takeIf { it.isNotEmpty() }, settings, scanCallback)

        scanTimeoutJob = scope.launch {
            delay(SCAN_TIMEOUT_MS)
            if (_connectionState.value is BleConnectionState.Scanning) {
                stopScan()
                _connectionState.value = BleConnectionState.Error("No device found")
            }
        }
    }

    fun disconnect() {
        val address = activeDeviceAddress ?: return
        userDisconnecting = true
        retryJob?.cancel()
        retryJob = null
        stopScan()
        _connectionState.value = BleConnectionState.Disconnected(address, null)
        pendingMetrics.clear()
        pendingSleep.clear()
        pendingActivities.clear()
        pendingRaw.clear()
        scope.launch { @Suppress("MissingPermission") activeGatt?.disconnect() }
        // activeManifest / activeDeviceAddress are cleared by gattCallback
        // when userDisconnecting == true
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

    // -------------------------------------------------------------------------
    // Scanning
    // -------------------------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // Capture before launching — result object may be recycled by the stack
            val device = result.device
            val name = device.name
            val serviceUuids = result.scanRecord?.serviceUuids?.map { it.uuid.toString() }.orEmpty()
            scope.launch {
                if (_connectionState.value !is BleConnectionState.Scanning) return@launch
                val (manifest, confidence) = driverRegistry.resolve(name, serviceUuids)
                    ?: return@launch
                Timber.d("BleEngine: matched driver ${manifest.id} for $name ($confidence)")
                stopScan()
                connect(device, manifest, resetRetries = true)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scope.launch {
                stopScan()
                _connectionState.value = BleConnectionState.Error("Scan failed ($errorCode)")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        bleScanner?.stopScan(scanCallback)
        bleScanner = null
    }

    // -------------------------------------------------------------------------
    // Connection
    // -------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice, manifest: WasmDriverManifest, resetRetries: Boolean) {
        activeManifest = manifest
        activeDeviceAddress = device.address
        if (resetRetries) {
            retryCount = 0
            syncStartedAt = Instant.now()
        }
        pendingMetrics.clear()
        pendingSleep.clear()
        pendingActivities.clear()
        pendingRaw.clear()
        notifySetupQueue.clear()
        commandIndex = 0
        inSyncCommandNotify = false
        userDisconnecting = false
        _connectionState.value = BleConnectionState.Connecting(device.address)
        activeGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            scope.launch {
                when {
                    newState == BluetoothProfile.STATE_CONNECTED &&
                            status == BluetoothGatt.GATT_SUCCESS -> {
                        Timber.d("BleEngine: connected, requesting MTU $MTU_REQUEST")
                        gatt.requestMtu(MTU_REQUEST)
                    }
                    newState == BluetoothProfile.STATE_DISCONNECTED && userDisconnecting -> {
                        Timber.d("BleEngine: user-initiated disconnect complete")
                        closeGatt()
                        activeManifest = null
                        activeDeviceAddress = null
                        _connectionState.value = BleConnectionState.Idle
                    }
                    newState == BluetoothProfile.STATE_DISCONNECTED -> {
                        Timber.w("BleEngine: unexpected disconnect, scheduling retry")
                        closeGatt()
                        scheduleRetry()
                    }
                    else -> {
                        Timber.e("BleEngine: GATT error status=$status newState=$newState")
                        closeGatt()
                        scheduleRetry()
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            scope.launch {
                Timber.d("BleEngine: MTU=$mtu status=$status, discovering services")
                gatt.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            scope.launch {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Timber.e("BleEngine: service discovery failed status=$status")
                    closeGatt()
                    scheduleRetry()
                    return@launch
                }
                val manifest = activeManifest ?: return@launch
                Timber.d("BleEngine: services discovered, enabling notifications")
                notifySetupQueue.clear()
                manifest.ble.characteristics.values.forEach { uuid ->
                    val char = findCharacteristic(uuid)
                    if (char != null &&
                        char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                    ) {
                        notifySetupQueue.addLast(uuid)
                    }
                }
                enableNextNotification()
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            scope.launch {
                when {
                    notifySetupQueue.isNotEmpty() -> enableNextNotification()
                    inSyncCommandNotify -> {
                        inSyncCommandNotify = false
                        commandIndex++
                        executeNextSyncCommand()
                    }
                    else -> executeNextSyncCommand()
                }
            }
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val bytes = characteristic.value?.clone() ?: return
                scope.launch { handleNotification(characteristic.uuid.toString(), bytes) }
            }
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            scope.launch { handleNotification(characteristic.uuid.toString(), value) }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            scope.launch {
                commandIndex++
                executeNextSyncCommand()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Notification setup
    // -------------------------------------------------------------------------

    private fun enableNextNotification() {
        while (notifySetupQueue.isNotEmpty()) {
            val uuid = notifySetupQueue.removeFirst()
            if (enableNotification(uuid)) return  // waiting for onDescriptorWrite
            Timber.w("BleEngine: no CCCD for $uuid, skipping")
        }
        // Queue exhausted — begin sync command execution
        executeNextSyncCommand()
    }

    @SuppressLint("MissingPermission")
    private fun enableNotification(uuid: String): Boolean {
        val gatt = activeGatt ?: return false
        val char = findCharacteristic(uuid) ?: return false
        gatt.setCharacteristicNotification(char, true)
        val descriptor = char.getDescriptor(UUID.fromString(CCCD_UUID)) ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
        return true
    }

    // -------------------------------------------------------------------------
    // Sync command execution
    // -------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun executeNextSyncCommand() {
        val manifest = activeManifest ?: return
        val address = activeDeviceAddress ?: return
        val commands = manifest.syncCommands

        if (commandIndex >= commands.size) {
            Timber.d("BleEngine: sync commands done, device ready")
            _connectionState.value = BleConnectionState.Connected(address, manifest.displayName)
            return
        }

        when (val cmd = commands[commandIndex]) {
            is SyncCommand.Write -> {
                val charUuid = manifest.ble.characteristics[cmd.characteristic] ?: run {
                    Timber.w("BleEngine: unknown characteristic role '${cmd.characteristic}'")
                    commandIndex++
                    executeNextSyncCommand()
                    return
                }
                val char = findCharacteristic(charUuid) ?: run {
                    Timber.w("BleEngine: characteristic $charUuid not found, skipping write")
                    commandIndex++
                    executeNextSyncCommand()
                    return
                }
                val bytes = parseHexBytes(cmd.bytes)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    activeGatt?.writeCharacteristic(
                        char,
                        bytes,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    char.value = bytes
                    @Suppress("DEPRECATION")
                    activeGatt?.writeCharacteristic(char)
                }
                // onCharacteristicWrite advances commandIndex
            }
            is SyncCommand.EnableNotify -> {
                val charUuid = manifest.ble.characteristics[cmd.characteristic] ?: run {
                    Timber.w("BleEngine: unknown characteristic role '${cmd.characteristic}'")
                    commandIndex++
                    executeNextSyncCommand()
                    return
                }
                inSyncCommandNotify = true
                if (!enableNotification(charUuid)) {
                    // Characteristic or CCCD not present; skip without waiting for callback
                    inSyncCommandNotify = false
                    commandIndex++
                    executeNextSyncCommand()
                }
                // onDescriptorWrite (inSyncCommandNotify branch) advances commandIndex
            }
            is SyncCommand.Delay -> {
                scope.launch {
                    delay(cmd.millis)
                    commandIndex++
                    executeNextSyncCommand()
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Retry and cleanup
    // -------------------------------------------------------------------------

    private fun scheduleRetry() {
        val address = activeDeviceAddress ?: return
        val manifest = activeManifest ?: return
        if (retryCount >= MAX_RETRIES) {
            Timber.e("BleEngine: max retries exceeded for $address")
            _connectionState.value = BleConnectionState.Disconnected(address, "Max retries exceeded")
            activeManifest = null
            activeDeviceAddress = null
            retryCount = 0
            return
        }
        retryCount++
        val delayMs = (1 shl retryCount) * 1000L  // 2 s, 4 s, 8 s
        Timber.d("BleEngine: retry $retryCount/$MAX_RETRIES in ${delayMs}ms")
        _connectionState.value = BleConnectionState.Connecting(address)
        retryJob = scope.launch {
            delay(delayMs)
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                ?.adapter ?: return@launch
            @Suppress("MissingPermission")
            val device = adapter.getRemoteDevice(address)
            connect(device, manifest, resetRetries = false)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun closeGatt() {
        withContext(Dispatchers.Main) {
            activeGatt?.disconnect()
            activeGatt?.close()
            activeGatt = null
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun findCharacteristic(uuid: String): BluetoothGattCharacteristic? =
        activeGatt?.services
            ?.flatMap { it.characteristics }
            ?.firstOrNull { it.uuid.toString().equals(uuid, ignoreCase = true) }

    private fun parseHexBytes(hex: String): ByteArray =
        hex.trim()
            .split("\\s+".toRegex())
            .filter { it.isNotEmpty() }
            .map { it.removePrefix("0x").removePrefix("0X").toInt(16).toByte() }
            .toByteArray()
}
