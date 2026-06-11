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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.Instant
import java.util.Collections
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

    private val pendingMetrics: MutableList<MetricReading> = Collections.synchronizedList(mutableListOf())
    private val pendingSleep: MutableList<SleepSession> = Collections.synchronizedList(mutableListOf())
    private val pendingActivities: MutableList<Activity> = Collections.synchronizedList(mutableListOf())
    private val pendingRaw: MutableList<RawPayload> = Collections.synchronizedList(mutableListOf())

    private var negotiatedMtu: Int = 23  // Android stack default; updated in onMtuChanged
    private val reassemblyBuffers = mutableMapOf<String, ByteArray>()

    // Packets arriving faster than WASM can parse them are held here. DROP_OLDEST
    // prevents the BLE callback from blocking; 512 items covers burst scenarios.
    // NOTE: packets dropped by the Android OS *before* onCharacteristicChanged fires
    // are unrecoverable — this channel only prevents drops caused by slow processing.
    private val notificationChannel = Channel<Pair<String, ByteArray>>(
        capacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

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

    init {
        // Auto-sync whenever a fresh Connecting → Connected transition occurs,
        // regardless of whether the Devices screen is open.
        scope.launch {
            var prev: BleConnectionState = BleConnectionState.Idle
            _connectionState.collect { state ->
                if (state is BleConnectionState.Connected &&
                    prev is BleConnectionState.Connecting) {
                    triggerSync()
                }
                prev = state
            }
        }

        // Single consumer: drains notificationChannel sequentially on IO so WASM
        // parsing never runs concurrently and the BLE callback is never blocked.
        scope.launch(Dispatchers.IO) {
            var processedCount = 0
            for ((uuid, bytes) in notificationChannel) {
                handleNotification(uuid, bytes)
                processedCount++
                if (processedCount % 50 == 0) {
                    Timber.d("BleEngine: Notification channel depth: ${notificationChannel.size}")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun autoConnectOnStartup() {
        scope.launch {
            if (_connectionState.value !is BleConnectionState.Idle) return@launch
            val devices = deviceRepository.getAllDevices().first()
            if (devices.isEmpty()) return@launch
            val device = devices.maxByOrNull { it.lastSyncMs ?: it.lastSeenMs ?: 0L } ?: return@launch
            val manifest = driverRegistry.allDrivers().find { it.id == device.driverId } ?: return@launch
            connectToDevice(device.bleAddress, manifest)
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(bleAddress: String, manifest: WasmDriverManifest) {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            _connectionState.value = BleConnectionState.Error("Bluetooth is disabled")
            return
        }
        val device = adapter.getRemoteDevice(bleAddress)
        connect(device, manifest, resetRetries = true)
    }

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
        reassemblyBuffers.clear()
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

    /**
     * Reassembly strategy: "short-packet terminal"
     *
     * The ATT layer delivers at most (negotiatedMtu - 3) bytes per notification callback.
     * A fragment whose size equals that maximum may be followed by more fragments;
     * a fragment shorter than that maximum is the final (or only) fragment.
     *
     * This matches the standard BLE streaming pattern used by the Hume Band: all its
     * packets are 3-4 bytes — always shorter than any realistic ATT payload size — so
     * each notification is immediately a complete packet (no buffering occurs in practice).
     * The buffer is present so that drivers for devices with larger packets work correctly
     * without engine changes.
     *
     * To support a device with a different framing convention (e.g. length prefix,
     * continuation-flag header, or fixed frame size), replace the completeness check
     * below and document the new strategy here.
     */
    private suspend fun handleNotification(characteristicUuid: String, bytes: ByteArray) {
        val manifest = activeManifest ?: return

        val maxPayload = negotiatedMtu - 3
        val existing = reassemblyBuffers[characteristicUuid] ?: ByteArray(0)
        val accumulated = existing + bytes
        reassemblyBuffers[characteristicUuid] = accumulated

        // A fragment smaller than the max ATT payload is the terminal fragment.
        if (bytes.size >= maxPayload) return  // still receiving; wait for next callback

        // Full packet assembled — pass to WASM parser and reset this characteristic's buffer.
        reassemblyBuffers[characteristicUuid] = ByteArray(0)

        // Already on Dispatchers.IO (consumer coroutine); no inner launch needed.
        val readings = driverRegistry.parseMetrics(manifest, characteristicUuid, accumulated)
        val sleep    = driverRegistry.parseSleep(manifest, characteristicUuid, accumulated)
        val activity = driverRegistry.parseActivity(manifest, characteristicUuid, accumulated)

        pendingMetrics.addAll(readings)
        sleep?.let { pendingSleep.add(it) }
        activity?.let { pendingActivities.add(it) }

        if (!driverRegistry.isWasmLoaded(manifest)) {
            _connectionState.value = BleConnectionState.Error(
                "Driver '${manifest.displayName}' WASM failed to initialise"
            )
            return
        }
        pendingRaw.add(RawPayload(
            characteristicUuid = characteristicUuid,
            payload = accumulated,
            receivedAt = Instant.now(),
        ))
    }

    suspend fun triggerSync(): SyncSummary? {
        if (_connectionState.value !is BleConnectionState.Connected) return null
        val manifest = activeManifest ?: return null
        val address = activeDeviceAddress ?: return null
        _connectionState.value = BleConnectionState.Syncing(address, 0f)
        return try {
            val metricReadings = synchronized(pendingMetrics) { pendingMetrics.toList().also { pendingMetrics.clear() } }
            val sleepSessions = synchronized(pendingSleep) { pendingSleep.toList().also { pendingSleep.clear() } }
            val activities = synchronized(pendingActivities) { pendingActivities.toList().also { pendingActivities.clear() } }
            val rawPayloads = synchronized(pendingRaw) { pendingRaw.toList().also { pendingRaw.clear() } }
            val result = DriverSyncResult(
                deviceId = address,
                driverId = manifest.id,
                syncStartedAt = syncStartedAt,
                syncEndedAt = Instant.now(),
                metricReadings = metricReadings,
                sleepSessions = sleepSessions,
                activities = activities,
                rawPayloads = rawPayloads,
            )
            reassemblyBuffers.forEach { (uuid, buf) ->
                if (buf.isNotEmpty()) {
                    Timber.w("BleEngine: Discarding incomplete packet on $uuid: ${buf.size} bytes")
                }
            }
            reassemblyBuffers.clear()
            val summary = syncProcessor.process(result)
            _connectionState.value = BleConnectionState.SyncComplete(summary, activeDeviceAddress ?: "")
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
        reassemblyBuffers.clear()
        negotiatedMtu = 23
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
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Timber.w("BleEngine: MTU negotiation failed status=$status, proceeding with default MTU")
                } else {
                    negotiatedMtu = mtu
                    Timber.i("MTU negotiated: $mtu bytes")
                }
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
                if (notificationChannel.size >= 512) {
                    Timber.w("BleEngine: Notification channel full — dropping oldest packet")
                }
                notificationChannel.trySend(Pair(characteristic.uuid.toString(), bytes))
            }
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (notificationChannel.size >= 512) {
                Timber.w("BleEngine: Notification channel full — dropping oldest packet")
            }
            notificationChannel.trySend(Pair(characteristic.uuid.toString(), value.clone()))
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            scope.launch {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Timber.w(
                        "BleEngine: write to ${characteristic.uuid} failed status=$status — " +
                            "sync command skipped, device may not stream data"
                    )
                }
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
