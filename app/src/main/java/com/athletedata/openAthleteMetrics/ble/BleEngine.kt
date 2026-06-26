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
import androidx.work.WorkManager
import com.athletedata.openAthleteMetrics.ble.driver.DriverRegistry
import com.athletedata.openAthleteMetrics.ble.driver.ParsingConfig
import com.athletedata.openAthleteMetrics.ble.driver.SyncCommand
import com.athletedata.openAthleteMetrics.ble.driver.WasmDriverManifest
import com.athletedata.openAthleteMetrics.ble.sync.DeviceSyncProcessor
import com.athletedata.openAthleteMetrics.ble.sync.MetricRouter
import com.athletedata.openAthleteMetrics.ble.sync.SyncSummary
import com.athletedata.openAthleteMetrics.ble.sync.SyncValidator
import com.athletedata.openAthleteMetrics.ble.sync.ValidationResult
import com.athletedata.openAthleteMetrics.data.model.Activity
import com.athletedata.openAthleteMetrics.data.model.DriverSyncResult
import com.athletedata.openAthleteMetrics.data.model.MetricReading
import com.athletedata.openAthleteMetrics.data.model.MetricType
import com.athletedata.openAthleteMetrics.data.model.RawPayload
import com.athletedata.openAthleteMetrics.data.model.SleepSession
import com.athletedata.openAthleteMetrics.data.repository.DeviceRepository
import com.athletedata.openAthleteMetrics.data.repository.RawDeviceDataRepository
import com.athletedata.openAthleteMetrics.worker.enqueueSummaryWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import kotlin.time.Duration.Companion.milliseconds
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class DiscoveredCandidate(
    val address: String,
    val manifest: WasmDriverManifest,
    val deviceName: String,
)

@Singleton
class BleEngine @Inject constructor(
    @param:ApplicationContext val context: Context,
    private val driverRegistry: DriverRegistry,
    private val syncContextFactory: SyncContextFactory, // CHANGED
    private val syncProcessor: DeviceSyncProcessor,
    private val validator: SyncValidator,
    private val deviceRepository: DeviceRepository,
    private val rawDeviceDataRepository: RawDeviceDataRepository,
    private val metricRouter: MetricRouter,
    private val workManager: WorkManager,
) {
    companion object {
        private const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
        private const val MTU_REQUEST = 512
        private const val SCAN_TIMEOUT_MS = 10_000L
        private const val MAX_RETRIES = 3
        private const val STREAM_QUIESCENCE_MS = 3_000L
        private const val SILENT_SYNC_TIMEOUT_MS = 15_000L

    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var activeManifest: WasmDriverManifest? = null
    private var activeDeviceAddress: String? = null
    private var syncStartedAt: Instant = Instant.now()

    private val pendingMetrics = LinkedHashMap<Pair<MetricType, Long>, MetricReading>()
    private val pendingSleep: MutableList<SleepSession> = mutableListOf()
    private val seenSleepStartMs = HashSet<Long>()
    private val pendingActivities = LinkedHashMap<Long, Activity>()

    // Session ID created by beginSession() on the first assembled packet; cleared on each fresh
    // connection and on disconnect. Raw packets are persisted against this ID immediately on
    // arrival so they survive process death (Fix 18).
    @Volatile private var currentSyncSessionId: Long? = null

    // Dates that received at least one new reading this sync. Populated by routeReading();
    // snapshotted and cleared at quiescence to enqueue DailySummaryWorker.
    private val affectedDates = mutableSetOf<LocalDate>()

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

    // Packet counting and quiescence tracking for streaming heuristic (Fix 15).
    // packetCount is written only on the single IO consumer coroutine; isQuiescent and
    // quiescenceJob are written from both IO and Main — @Volatile ensures visibility.
    @Volatile private var packetCount = 0
    @Volatile private var isQuiescent = false
    @Volatile private var quiescenceJob: Job? = null
    @Volatile private var gattCacheRefreshAttempted = false
    @Volatile private var silentSyncTimeoutJob: Job? = null

    private var activeGatt: BluetoothGatt? = null
    private var bleScanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var scanTimeoutJob: Job? = null
    private var retryCount = 0
    private var retryJob: Job? = null
    private val notifySetupQueue = ArrayDeque<String>()
    private var commandIndex = 0
    private var effectiveSyncCommands: List<SyncCommand> = emptyList()
    private var inSyncCommandNotify = false
    private var userDisconnecting = false

    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Idle)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _discoveredCandidates = MutableStateFlow<List<DiscoveredCandidate>>(emptyList())
    val discoveredCandidates: StateFlow<List<DiscoveredCandidate>> = _discoveredCandidates.asStateFlow()
    // Keyed by MAC address. All access is on the main thread (inside scope.launch) so no
    // extra synchronisation is needed. Cleared on each new scan and on candidate selection.
    private val candidateMap = LinkedHashMap<String, DiscoveredCandidate>()

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
        try {
            val device = adapter.getRemoteDevice(bleAddress)
            connect(device, manifest, resetRetries = true)
        } catch (e: SecurityException) {
            Timber.w(e, "BLE permission revoked during connectToDevice")
            _connectionState.value = BleConnectionState.Error("Bluetooth permission denied")
        }
    }

    fun connectToCandidate(candidate: DiscoveredCandidate) {
        candidateMap.clear()
        _discoveredCandidates.value = emptyList()
        stopScan()
        connectToDevice(candidate.address, candidate.manifest)
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        Timber.w(Exception("startScan stack trace"), "BleEngine: startScan() called")
        val current = _connectionState.value
        if (current !is BleConnectionState.Idle && current !is BleConnectionState.Error &&
            current !is BleConnectionState.GattCacheError) return

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

        candidateMap.clear()
        _discoveredCandidates.value = emptyList()
        _connectionState.value = BleConnectionState.Scanning
        try {
            scanner.startScan(filters.takeIf { it.isNotEmpty() }, settings, scanCallback)
        } catch (e: SecurityException) {
            Timber.w(e, "BLE permission revoked during startScan")
            _connectionState.value = BleConnectionState.Error("Bluetooth permission denied")
            return
        }

        scanTimeoutJob = scope.launch {
            delay(SCAN_TIMEOUT_MS.milliseconds)
            if (_connectionState.value is BleConnectionState.Scanning) {
                stopScan()
                if (_discoveredCandidates.value.isEmpty()) {
                    _connectionState.value = BleConnectionState.Error("No device found")
                }
            }
        }
    }

    fun disconnect() {
        val address = activeDeviceAddress ?: return
        userDisconnecting = true
        retryJob?.cancel()
        retryJob = null
        silentSyncTimeoutJob?.cancel()
        silentSyncTimeoutJob = null
        gattCacheRefreshAttempted = false
        quiescenceJob?.cancel()
        quiescenceJob = null
        packetCount = 0
        isQuiescent = false
        stopScan()
        _connectionState.value = BleConnectionState.Disconnected(address, null)
        synchronized(pendingMetrics) { pendingMetrics.clear() }
        synchronized(pendingSleep) { pendingSleep.clear(); seenSleepStartMs.clear() }
        synchronized(pendingActivities) { pendingActivities.clear() }
        currentSyncSessionId = null
        synchronized(affectedDates) { affectedDates.clear() }
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

    fun shutdown() {
        scope.cancel()
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

        // Lazily create the session row on the first assembled packet (already on Dispatchers.IO).
        if (currentSyncSessionId == null) {
            val address = activeDeviceAddress ?: return
            currentSyncSessionId = syncProcessor.beginSession(address, manifest.id, syncStartedAt)
        }
        val sessionId = currentSyncSessionId ?: return

        // Already on Dispatchers.IO (consumer coroutine); no inner launch needed.
        val readings = driverRegistry.parseMetrics(manifest, characteristicUuid, accumulated)
        val sleep    = driverRegistry.parseSleep(manifest, characteristicUuid, accumulated)
        val activity = driverRegistry.parseActivity(manifest, characteristicUuid, accumulated)

        val readingResults = validator.validateReadings(readings)
        for (result in readingResults) {
            when (result) {
                is ValidationResult.Accepted -> {
                    val reading = result.item
                    routeReading(reading)
                    if (reading.metricType !in MetricType.DEDICATED_METRIC_TYPES) {
                        synchronized(pendingMetrics) {
                            pendingMetrics[Pair(reading.metricType, reading.recordedAt.toEpochMilli())] = reading
                        }
                    }
                }
                is ValidationResult.Rejected ->
                    Timber.w("handleNotification: dropped ${result.item.metricType} — ${result.reason}")
            }
        }
        synchronized(pendingSleep) {
            sleep?.let { session ->
                if (seenSleepStartMs.add(session.sleepStartMs.toEpochMilli())) {
                    pendingSleep.add(session)
                }
            }
        }
        synchronized(pendingActivities) {
            activity?.let { act -> pendingActivities[act.startTime.toEpochMilli()] = act }
        }

        if (!driverRegistry.isWasmLoaded(manifest)) {
            _connectionState.value = BleConnectionState.Error(
                "Driver '${manifest.displayName}' WASM failed to initialise"
            )
            return
        }

        // Persist raw packet immediately so it survives process death (Fix 18).
        rawDeviceDataRepository.insertAll(
            listOf(RawPayload(characteristicUuid, accumulated, Instant.now())),
            sessionId,
        )

        silentSyncTimeoutJob?.cancel()
        silentSyncTimeoutJob = null

        // Update live packet counter and restart quiescence timer.
        val count = ++packetCount
        val address = activeDeviceAddress ?: return
        quiescenceJob?.cancel()
        isQuiescent = false
        if (_connectionState.value is BleConnectionState.Connected) {
            _connectionState.value = BleConnectionState.Connected(address, manifest.displayName, count, false)
            quiescenceJob = scope.launch {
                delay(STREAM_QUIESCENCE_MS.milliseconds)
                if (_connectionState.value is BleConnectionState.Connected) {
                    isQuiescent = true
                    _connectionState.value = BleConnectionState.Connected(address, manifest.displayName, count, true)
                    val datesToProcess = synchronized(affectedDates) {
                        affectedDates.toSet().also { affectedDates.clear() }
                    }
                    datesToProcess.forEach { enqueueSummaryWorker(it, workManager) }
                }
            }
        }
    }

    suspend fun triggerSync(): SyncSummary? {
        if (_connectionState.value !is BleConnectionState.Connected) return null
        val manifest = activeManifest ?: return null
        val address = activeDeviceAddress ?: return null
        val capturedPacketCount = packetCount
        val capturedBeforeQuiescence = !isQuiescent
        quiescenceJob?.cancel()
        quiescenceJob = null
        _connectionState.value = BleConnectionState.Syncing(address, 0f)
        val preSyncSessionId = currentSyncSessionId
        currentSyncSessionId = null
        return try {
            val metricReadings = synchronized(pendingMetrics) { pendingMetrics.values.toList().also { pendingMetrics.clear() } }
            val sleepSessions = synchronized(pendingSleep) { pendingSleep.toList().also { pendingSleep.clear(); seenSleepStartMs.clear() } }
            val activities = synchronized(pendingActivities) { pendingActivities.values.toList().also { pendingActivities.clear() } }
            val result = DriverSyncResult(
                deviceId = address,
                driverId = manifest.id,
                syncStartedAt = syncStartedAt,
                syncEndedAt = Instant.now(),
                metricReadings = metricReadings,
                sleepSessions = sleepSessions,
                activities = activities,
                rawPayloads = emptyList(), // packets already persisted on arrival
                packetsReceived = capturedPacketCount,
                syncedBeforeQuiescence = capturedBeforeQuiescence,
            )
            reassemblyBuffers.forEach { (uuid, buf) ->
                if (buf.isNotEmpty()) {
                    Timber.w("BleEngine: Discarding incomplete packet on $uuid: ${buf.size} bytes")
                }
            }
            reassemblyBuffers.clear()
            val summary = syncProcessor.process(result, preSyncSessionId = preSyncSessionId)
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
            val address = device.address
            val name = device.name
            val serviceUuids = result.scanRecord?.serviceUuids?.map { it.uuid.toString() }.orEmpty()
            Timber.d("BleEngine: onScanResult address=$address name=$name")
            scope.launch {
                if (_connectionState.value !is BleConnectionState.Scanning) return@launch
                val (manifest, confidence) = driverRegistry.resolve(name, serviceUuids)
                    ?: return@launch
                Timber.d("BleEngine: matched driver ${manifest.id} for $name ($confidence)")
                candidateMap[address] = DiscoveredCandidate(address, manifest, name ?: manifest.displayName)
                Timber.d("BleEngine: candidateMap after insert = ${candidateMap.keys}")
                _discoveredCandidates.value = candidateMap.values.toList()
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
    @Suppress("DEPRECATION")
    private fun connect(device: BluetoothDevice, manifest: WasmDriverManifest, resetRetries: Boolean) {
        silentSyncTimeoutJob?.cancel()
        silentSyncTimeoutJob = null
        activeManifest = manifest
        activeDeviceAddress = device.address
        if (resetRetries) {
            retryCount = 0
            gattCacheRefreshAttempted = false
            syncStartedAt = Instant.now()
            driverRegistry.startSync()
            synchronized(pendingMetrics) { pendingMetrics.clear() }
            synchronized(pendingSleep) { pendingSleep.clear(); seenSleepStartMs.clear() }
            synchronized(pendingActivities) { pendingActivities.clear() }
            currentSyncSessionId = null
            packetCount = 0
            isQuiescent = false
            quiescenceJob?.cancel()
            quiescenceJob = null
            synchronized(affectedDates) { affectedDates.clear() }
        } else {
            val existingCount = synchronized(pendingMetrics) { pendingMetrics.size }
            Timber.i("Reconnect: accumulator has $existingCount existing readings — duplicates from re-stream will be discarded in memory")
        }
        reassemblyBuffers.clear()
        negotiatedMtu = 23
        notifySetupQueue.clear()
        commandIndex = 0
        effectiveSyncCommands = emptyList()
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
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Timber.w(
                        "BleEngine: CCCD write failed for characteristic=${descriptor.characteristic.uuid} status=$status"
                    )
                    closeGatt()
                    scheduleRetry()
                    return@launch
                }
                when {
                    notifySetupQueue.isNotEmpty() -> enableNextNotification()
                    inSyncCommandNotify -> {
                        inSyncCommandNotify = false
                        commandIndex++
                        executeNextSyncCommand()
                    }
                    else -> beginSyncCommandExecution()
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
                val result = notificationChannel.trySend(Pair(characteristic.uuid.toString(), bytes))
                if (result.isFailure) Timber.w("BleEngine: notification channel full — BLE packet dropped")
            }
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            val result = notificationChannel.trySend(Pair(characteristic.uuid.toString(), value.clone()))
            if (result.isFailure) Timber.w("BleEngine: notification channel full — BLE packet dropped")
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            scope.launch {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Timber.e(
                        "BleEngine: write to ${characteristic.uuid} failed (command $commandIndex) status=$status — scheduling retry"
                    )
                    closeGatt()
                    scheduleRetry()
                    return@launch
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
        // Queue exhausted — build effective commands then begin execution.
        scope.launch { beginSyncCommandExecution() }
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

    // CHANGED: resolves static manifest commands + any context-aware commands, then executes.
    private suspend fun beginSyncCommandExecution() {
        val manifest = activeManifest ?: return
        val staticCmds = manifest.syncCommands                   // CHANGED
        val contextCmds = resolveContextCommands(manifest)       // CHANGED
        effectiveSyncCommands = staticCmds + contextCmds         // CHANGED
        executeNextSyncCommand()
    }

    // CHANGED: reads syncRequirements, builds the appropriate SyncContext (with or without a
    // DB fetch), and calls the driver's buildSyncCommands WASM export. Returns emptyList when
    // the export is absent or SyncContextFactory throws — the connection is never aborted.
    private suspend fun resolveContextCommands(
        manifest: WasmDriverManifest,
    ): List<SyncCommand.Write> {
        val wasm = manifest.parsing as? ParsingConfig.WasmParsing ?: return emptyList()
        if (wasm.exports.buildSyncCommands == null) return emptyList()

        val req = manifest.syncRequirements
        val needsUserData = req != null && (req.datetime || req.userProfile.isNotEmpty())

        val syncContext = if (needsUserData) {
            try {
                syncContextFactory.build()
            } catch (e: Exception) {
                Timber.e(e, "BleEngine: SyncContextFactory.build() failed — continuing with static commands only")
                return emptyList()
            }
        } else {
            buildMinimalContext()
        }

        return driverRegistry.buildSyncCommands(manifest, syncContext)
    }

    // CHANGED: constructs a time-only SyncContext without hitting the DB.
    private fun buildMinimalContext(): SyncContext {
        val epochMs = System.currentTimeMillis()
        val instant = Instant.ofEpochMilli(epochMs)
        val zone = ZoneId.systemDefault()
        val utcOffsetMinutes = zone.rules.getOffset(instant).totalSeconds / 60
        val isoDateTime = DateTimeFormatter.ISO_LOCAL_DATE_TIME
            .format(LocalDateTime.ofInstant(instant, zone))
        return SyncContext(
            epochMs = epochMs,
            utcOffsetMinutes = utcOffsetMinutes,
            isoDateTime = isoDateTime,
            name = null, dateOfBirth = null, biologicalSex = null,
            heightCm = null, weightKg = null, strideLengthCm = null,
            wristCircumferenceMm = null, restingMetabolicRate = null,
            vo2Max = null, maxHr = null, hrZones = emptyList(),
        )
    }

    @SuppressLint("MissingPermission")
    private fun executeNextSyncCommand() {
        val manifest = activeManifest ?: return
        val address = activeDeviceAddress ?: return
        val commands = effectiveSyncCommands.ifEmpty { manifest.syncCommands }

        if (commandIndex >= commands.size) {
            Timber.d("BleEngine: sync commands done, device ready")
            _connectionState.value = BleConnectionState.Connected(address, manifest.displayName)
            silentSyncTimeoutJob?.cancel()
            silentSyncTimeoutJob = scope.launch {
                delay(SILENT_SYNC_TIMEOUT_MS.milliseconds)
                if (_connectionState.value !is BleConnectionState.Connected || packetCount > 0) return@launch
                val gatt = activeGatt ?: return@launch
                val capturedAddress = activeDeviceAddress ?: return@launch
                val capturedManifest = activeManifest ?: return@launch
                Timber.w("BleEngine: No notifications received after ${SILENT_SYNC_TIMEOUT_MS}ms — possible GATT cache issue")
                if (!gattCacheRefreshAttempted) {
                    gattCacheRefreshAttempted = true
                    if (gatt.refreshCache()) {
                        Timber.i("BleEngine: GATT cache refreshed — reconnecting")
                        closeGatt()
                        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                            ?.adapter ?: return@launch
                        @Suppress("MissingPermission")
                        val device = adapter.getRemoteDevice(capturedAddress)
                        connect(device, capturedManifest, resetRetries = false)
                    } else {
                        _connectionState.value = BleConnectionState.GattCacheError(capturedAddress, capturedManifest.displayName)
                    }
                } else {
                    _connectionState.value = BleConnectionState.GattCacheError(capturedAddress, capturedManifest.displayName)
                }
            }
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
                    delay(cmd.millis.milliseconds)
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
            delay(delayMs.milliseconds)
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
            activeGatt?.close()
            activeGatt = null
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Routes a [MetricReading] to the appropriate typed repository.
     *
     * Known metric types → their dedicated table (hr_readings, hrv_readings, etc.).
     * [MetricType.SLEEP_STAGE] → metric_readings_staging with a pending_sleep_stage flag.
     *   Grouping those staged rows into SleepSession + SleepStage rows once a complete night
     *   is available is handled by [com.athletedata.openAthleteMetrics.worker.SleepStagePromoter],
     *   which is invoked by both [DeviceSyncProcessor] and [com.athletedata.openAthleteMetrics.ble.sync.DeviceReprocessor].
     * [MetricType.BLOOD_PRESSURE] → blood_pressure_readings when metaJson["diastolic"] is
     *   present and parseable; falls back to metric_readings_staging otherwise.
     * All other types → metric_readings_staging (catch-all for unknown metrics).
     *
     * Also records the reading's UTC date in [affectedDates] for DailySummaryWorker scheduling.
     * Must be called from an IO dispatcher context.
     */
    private suspend fun routeReading(reading: MetricReading) {
        metricRouter.route(reading)
        val date = reading.recordedAt.atZone(ZoneId.systemDefault()).toLocalDate()
        synchronized(affectedDates) { affectedDates.add(date) }
    }

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

    // Android caches GATT service/characteristic handles per device MAC address. A firmware
    // update that changes handles causes sync commands to target the wrong handles silently —
    // no error is returned and no notifications arrive. BluetoothGatt.refresh() (hidden API,
    // availability varies by Android version and OEM) clears the cache so the next connect
    // re-discovers services fresh. If unavailable, a Bluetooth adapter reset (user action) is
    // the only alternative that works on all Android versions.
    private fun BluetoothGatt.refreshCache(): Boolean =
        try {
            val method = javaClass.getMethod("refresh")
            method.invoke(this) as Boolean
        } catch (e: Exception) {
            Timber.w("BleEngine: GATT refresh not available: ${e.message}")
            false
        }
}
