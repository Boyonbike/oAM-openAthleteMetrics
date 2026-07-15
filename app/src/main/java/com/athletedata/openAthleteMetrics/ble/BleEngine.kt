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
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.work.WorkManager
import com.athletedata.openAthleteMetrics.ble.driver.DriverRegistry
import com.athletedata.openAthleteMetrics.ble.driver.EndOfStreamStrategy
import com.athletedata.openAthleteMetrics.ble.driver.EndOfStreamStrategyFactory
import com.athletedata.openAthleteMetrics.ble.wasm.SessionFrame
import com.athletedata.openAthleteMetrics.ble.wasm.WasmParseResult
import com.athletedata.openAthleteMetrics.ble.driver.ParsingConfig
import com.athletedata.openAthleteMetrics.ble.driver.CaloriesMode // CALORIES-MODE
import com.athletedata.openAthleteMetrics.ble.driver.StepsMode // STEPS-MODE
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    private val syncContextFactory: SyncContextFactory,
    private val syncProcessor: DeviceSyncProcessor,
    private val validator: SyncValidator,
    private val deviceRepository: DeviceRepository,
    private val rawDeviceDataRepository: RawDeviceDataRepository,
    private val metricRouter: MetricRouter,
    private val workManager: WorkManager,
) {
    companion object {
        private const val TAG = "data-pathway-tracker" // DPT
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

    // per-command await-reply state. Written on Main (executeNextSyncCommand),
    // read on the BLE callback thread (onCharacteristicChanged). @Volatile for visibility;
    // CompletableDeferred.complete() is thread-safe so no lock is needed.
    @Volatile private var awaitReplyDeferred: CompletableDeferred<Unit>? = null
    @Volatile private var awaitReplyCharUuid: String? = null
    @Volatile private var awaitReplyTimeoutMs: Long = 5000L

    // per-command await-end-of-stream state, parallel to awaitReply fields.
    // Armed before writeCharacteristic(); checked in onCharacteristicChanged on callback thread.
    @Volatile private var awaitEndOfStreamDeferred: CompletableDeferred<Unit>? = null
    @Volatile private var awaitEndOfStreamCharUuid: String? = null
    // Pluggable end-of-stream detection strategy.
    @Volatile private var awaitEndOfStreamStrategy: EndOfStreamStrategy? = null
    @Volatile private var awaitEndOfStreamArmedAtMs: Long = 0L
    @Volatile private var awaitEndOfStreamTimeoutMs: Long = 30_000L

    // session-scoped frame buffer; cleared at fresh sync start, parsed post-disconnect.
    private val sessionCache = mutableListOf<SessionFrame>()
    // set when awaitEndOfStream deferred completes; triggers close-then-parse path.
    @Volatile private var endOfStreamReceived = false
    // set before intentional post-stream GATT close; suppresses scheduleRetry().
    @Volatile private var postStreamDisconnecting = false

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
     * This matches the standard BLE streaming pattern used by short-packet devices: all
     * their packets are 3-4 bytes — always shorter than any realistic ATT payload size — so
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
        Timber.tag(TAG).v("handleNotification: mtu=%d maxPayload=%d bytesSize=%d char=%s", negotiatedMtu, maxPayload, bytes.size, characteristicUuid)
        if (bytes.size >= maxPayload) return  // still receiving; wait for next callback

        // Full packet assembled — buffer for post-stream parse and reset this characteristic's buffer.
        reassemblyBuffers[characteristicUuid] = ByteArray(0)

        // append assembled frame to session cache for post-disconnect parseSession call.
        if (accumulated.isNotEmpty()) {
            val opcode = "0x%02X".format(accumulated[0])
            val charRole = resolveCharacteristicRole(characteristicUuid)
            synchronized(sessionCache) {
                sessionCache.add(SessionFrame(charRole, opcode, accumulated.copyOf()))
            }
        }

        // Lazily create the session row on the first assembled packet (already on Dispatchers.IO).
        if (currentSyncSessionId == null) {
            val address = activeDeviceAddress ?: return
            currentSyncSessionId = syncProcessor.beginSession(address, manifest.id, syncStartedAt)
        }
        val sessionId = currentSyncSessionId ?: return

        // Persist raw packet immediately so it survives process death (Fix 18).
        rawDeviceDataRepository.insertAll(
            listOf(RawPayload(characteristicUuid, accumulated, Instant.now())),
            sessionId,
        )

        // CHANGED: the legacy quiescence/silent-sync-timeout timers are a fallback
        // completion signal for commands with no driver-declared awaitEndOfStream strategy.
        // While one is armed (awaitEndOfStreamStrategy != null), completion is driven solely
        // by awaitEndOfStream — these timers must not run, reset, or have any side effect.
        val count = ++packetCount
        val address = activeDeviceAddress ?: return
        if (awaitEndOfStreamStrategy == null) {
            silentSyncTimeoutJob?.cancel()
            silentSyncTimeoutJob = null

            // Update live packet counter and restart quiescence timer.
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
                        val manifestStepsMode = activeManifest?.stepsMode ?: StepsMode.DELTA // STEPS-MODE
                        datesToProcess.forEach { date ->
                            Timber.tag(TAG).d("[STAGE-6 DB-WRITE] enqueuing DailySummaryWorker for date=%s stepsMode=%s", date, manifestStepsMode) // DPT / STEPS-MODE
                            enqueueSummaryWorker(date, workManager, manifestStepsMode) // STEPS-MODE
                        }
                    }
                }
            }
        }
    }

    suspend fun triggerSync(): SyncSummary? {
        if (_connectionState.value !is BleConnectionState.Connected) return null
        val manifest = activeManifest ?: return null
        val address = activeDeviceAddress ?: return null
        val capturedPacketCount = packetCount
        // REMOVED: early-sync-warning
        quiescenceJob?.cancel()
        quiescenceJob = null
        // AFFECTED-DATES: typed repo writes (HR, HRV, SpO2, etc.) do not internally
        // enqueue DailySummaryWorker. affectedDates accumulates their dates during the
        // notification stream. We must drain it here because quiescenceJob is cancelled
        // before it gets the chance to enqueue them. This ensures summaries are always
        // computed after a manual sync, not just after natural BLE quiescence.
        // Double-enqueue safety: affectedDates is drained atomically with clear() in both
        // this path and quiescenceJob. If quiescenceJob already fired and drained the set
        // before triggerSync() is called, this snapshot will be empty — no double-enqueue.
        // If triggerSync() fires first (the manual-sync case), quiescenceJob is cancelled
        // above with an empty set to process — again no double-enqueue.
        val datesToEnqueue = synchronized(affectedDates) { affectedDates.toSet().also { affectedDates.clear() } } // AFFECTED-DATES
        if (datesToEnqueue.isEmpty()) {
            Timber.tag(TAG).d("[STAGE-6 SYNC] affectedDates empty — no summary workers to enqueue from manual sync") // AFFECTED-DATES
        } else {
            Timber.tag(TAG).d("[STAGE-6 SYNC] draining affectedDates count=%d dates=%s", datesToEnqueue.size, datesToEnqueue.joinToString()) // AFFECTED-DATES
        }
        _connectionState.value = BleConnectionState.Syncing(address, 0f)
        val preSyncSessionId = currentSyncSessionId
        currentSyncSessionId = null
        return try {
            // Parse session cache for drivers that use parseSession but not awaitEndOfStream
            // (streaming devices whose frames accumulated while connected). Battery readings are
            // separated so DeviceSyncProcessor can update the device row; all others are routed
            // to their typed tables immediately via MetricRouter, matching dispatchPostStreamParse.
            val wasm = manifest.parsing as? ParsingConfig.WasmParsing
            val batteryFromSession = mutableListOf<MetricReading>()
            if (wasm?.exports?.parseSession != null) {
                val frames = synchronized(sessionCache) { sessionCache.toList().also { sessionCache.clear() } }
                Timber.tag(TAG).d("[STAGE-6 SYNC] parsing sessionCache frames=%d via parseSession", frames.size)
                when (val parseResult = driverRegistry.parseSession(manifest, frames)) {
                    is WasmParseResult.Success -> {
                        val readingResults = validator.validateReadings(parseResult.readings)
                        for (vr in readingResults) {
                            when (vr) {
                                is ValidationResult.Accepted -> {
                                    routeReading(vr.item)
                                    if (vr.item.metricType == MetricType.BATTERY) batteryFromSession.add(vr.item)
                                }
                                is ValidationResult.Rejected ->
                                    Timber.w("triggerSync: dropped %s — %s", vr.item.metricType, vr.reason)
                            }
                        }
                    }
                    is WasmParseResult.Empty ->
                        Timber.tag(TAG).d("[STAGE-6 SYNC] parseSession empty — no readings from session cache")
                    is WasmParseResult.WasmTrap ->
                        Timber.tag(TAG).e("[STAGE-6 SYNC] parseSession wasm trap: %s", parseResult.message)
                    is WasmParseResult.DeserialisationError ->
                        Timber.tag(TAG).e("[STAGE-6 SYNC] parseSession deserialisation error: %s", parseResult.message)
                    is WasmParseResult.EngineNotInitialised ->
                        Timber.tag(TAG).e("[STAGE-6 SYNC] parseSession engine not initialised")
                }
            }
            synchronized(pendingMetrics) { pendingMetrics.clear() }
            val sleepSessions = synchronized(pendingSleep) { pendingSleep.toList().also { pendingSleep.clear(); seenSleepStartMs.clear() } }
            val activities = synchronized(pendingActivities) { pendingActivities.values.toList().also { pendingActivities.clear() } }
            val result = DriverSyncResult(
                deviceId = address,
                driverId = manifest.id,
                syncStartedAt = syncStartedAt,
                syncEndedAt = Instant.now(),
                metricReadings = batteryFromSession, // others already written by routeReading
                sleepSessions = sleepSessions,       // SLEEP_STAGE arrives as MetricReading via parseSession
                activities = activities,
                rawPayloads = emptyList(), // packets already persisted on arrival
                packetsReceived = capturedPacketCount,
                // REMOVED: early-sync-warning
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
        } finally { // AFFECTED-DATES
            val manifestStepsMode = activeManifest?.stepsMode ?: StepsMode.DELTA // AFFECTED-DATES
            datesToEnqueue.forEach { date -> // AFFECTED-DATES
                Timber.tag(TAG).d("[STAGE-6 SYNC] enqueueing DailySummaryWorker for date=%s", date) // AFFECTED-DATES
                enqueueSummaryWorker(date, workManager, manifestStepsMode) // AFFECTED-DATES
            } // AFFECTED-DATES
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
            synchronized(sessionCache) { sessionCache.clear() }
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
        Timber.tag(TAG).d("[CONN] connecting address=%s driver=%s retry=%d", device.address, manifest.id, retryCount)
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
                        Timber.tag(TAG).d("[CONN] connected address=%s requesting MTU %d", gatt.device.address, MTU_REQUEST)
                        gatt.requestMtu(MTU_REQUEST)
                    }
                    newState == BluetoothProfile.STATE_DISCONNECTED && userDisconnecting -> {
                        Timber.d("BleEngine: user-initiated disconnect complete")
                        Timber.tag(TAG).d("[CONN] disconnected address=%s user-initiated", gatt.device.address)
                        closeGatt()
                        activeManifest = null
                        activeDeviceAddress = null
                        _connectionState.value = BleConnectionState.Idle
                    }
                    // intentional post-stream disconnect — parse is in progress, skip retry.
                    newState == BluetoothProfile.STATE_DISCONNECTED && postStreamDisconnecting -> {
                        Timber.tag(TAG).d("[CONN] post-stream disconnect — parse in progress, no retry")
                        closeGatt() // no-op: activeGatt already null
                    }
                    newState == BluetoothProfile.STATE_DISCONNECTED -> {
                        Timber.w("BleEngine: unexpected disconnect, scheduling retry")
                        Timber.tag(TAG).d("[CONN] unexpected disconnect address=%s retryCount=%d", gatt.device.address, retryCount)
                        closeGatt()
                        scheduleRetry()
                    }
                    else -> {
                        Timber.e("BleEngine: GATT error status=$status newState=$newState")
                        Timber.tag(TAG).d("[CONN] GATT error status=%d newState=%d", status, newState)
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
                    Timber.tag(TAG).d("[CONN] MTU=%d discovering services", mtu)
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
                Timber.tag(TAG).d("[CONN] services discovered count=%d", gatt.services.size)
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
                    else -> {
                        Timber.tag(TAG).d("[CONN] all notifications enabled, starting sync commands")
                        beginSyncCommandExecution()
                    }
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
                val uuidStr = characteristic.uuid.toString() // DPT: moved up
                Timber.tag(TAG).d("[STAGE-1 RAW-PACKET] char=%s len=%d bytes=%s", uuidStr.take(8), bytes.size, bytes.toDptHexString()) // DPT
                if (bytes.isNotEmpty()) Timber.tag(TAG).d("opcode=0x%02X len=%d bytes=%s", bytes[0], bytes.size, bytes.toHexString())
                // unblock any pending awaitReply wait on this characteristic.
                // Notification still flows to the channel — pass-through is preserved.
                if (awaitReplyCharUuid.equals(uuidStr, ignoreCase = true)) awaitReplyDeferred?.complete(Unit)
                // Consults the armed EndOfStreamStrategy to detect the terminal packet.
                var isSentinelTermination = false
                var isStaleEndOfStreamNotification = false
                if (awaitEndOfStreamCharUuid.equals(uuidStr, ignoreCase = true)) {
                    val strategy = awaitEndOfStreamStrategy
                    if (strategy != null) {
                        val elapsedMs = SystemClock.elapsedRealtime() - awaitEndOfStreamArmedAtMs
                        if (strategy.onNotification(bytes, elapsedMs)) {
                            awaitEndOfStreamDeferred?.complete(Unit)
                            // SentinelPacket's terminator is a standalone protocol-control
                            // packet, not a data record — exclude it from reassembly/sessionCache/
                            // parseSession input. Other strategies' matched packet still passes
                            // through (documented in DRIVER_AUTHORING_GUIDE.md).
                            isSentinelTermination = strategy is EndOfStreamStrategy.SentinelPacket
                        }
                    } else {
                        // charUuid matched but nothing is currently armed — a stray/duplicate
                        // notification arriving in the gap between one command's stream resolving
                        // and the next command (if any) arming its own awaitEndOfStream. Drop it
                        // instead of silently forwarding it as if it were ordinary session data.
                        Timber.w("BleEngine: stray notification on $uuidStr with no command currently awaiting end-of-stream — dropping")
                        isStaleEndOfStreamNotification = true
                    }
                }
                if (!isSentinelTermination && !isStaleEndOfStreamNotification) {
                    val result = notificationChannel.trySend(Pair(uuidStr, bytes))
                    if (result.isFailure) Timber.w("BleEngine: notification channel full — BLE packet dropped")
                }
            }
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            val cloned = value.clone()
            val uuidStr = characteristic.uuid.toString() // DPT: moved up
            Timber.tag(TAG).d("[STAGE-1 RAW-PACKET] char=%s len=%d bytes=%s", uuidStr.take(8), cloned.size, cloned.toDptHexString()) // DPT
            if (cloned.isNotEmpty()) Timber.tag(TAG).d("opcode=0x%02X len=%d bytes=%s", cloned[0], cloned.size, cloned.toHexString())
            // unblock any pending awaitReply wait on this characteristic.
            if (awaitReplyCharUuid.equals(uuidStr, ignoreCase = true)) awaitReplyDeferred?.complete(Unit)
            // Consults the armed EndOfStreamStrategy to detect the terminal packet.
            var isSentinelTermination = false
            var isStaleEndOfStreamNotification = false
            if (awaitEndOfStreamCharUuid.equals(uuidStr, ignoreCase = true)) {
                val strategy = awaitEndOfStreamStrategy
                if (strategy != null) {
                    val elapsedMs = SystemClock.elapsedRealtime() - awaitEndOfStreamArmedAtMs
                    if (strategy.onNotification(cloned, elapsedMs)) {
                        awaitEndOfStreamDeferred?.complete(Unit)
                        // SentinelPacket's terminator is a standalone protocol-control
                        // packet, not a data record — exclude it from reassembly/sessionCache/
                        // parseSession input. Other strategies' matched packet still passes
                        // through (documented in DRIVER_AUTHORING_GUIDE.md).
                        isSentinelTermination = strategy is EndOfStreamStrategy.SentinelPacket
                    }
                } else {
                    // charUuid matched but nothing is currently armed — a stray/duplicate
                    // notification arriving in the gap between one command's stream resolving and
                    // the next command (if any) arming its own awaitEndOfStream. Drop it instead of
                    // silently forwarding it as if it were ordinary session data.
                    Timber.w("BleEngine: stray notification on $uuidStr with no command currently awaiting end-of-stream — dropping")
                    isStaleEndOfStreamNotification = true
                }
            }
            if (!isSentinelTermination && !isStaleEndOfStreamNotification) {
                val result = notificationChannel.trySend(Pair(uuidStr, cloned))
                if (result.isFailure) Timber.w("BleEngine: notification channel full — BLE packet dropped")
            }
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
                // three-way branch — awaitReply, awaitEndOfStream, or immediate advance.
                val arDeferred  = awaitReplyDeferred
                val aesDeferred = awaitEndOfStreamDeferred
                when {
                    arDeferred != null -> {
                        // Existing awaitReply logic — completely unchanged.
                        val charUuid  = awaitReplyCharUuid
                        val timeoutMs = awaitReplyTimeoutMs
                        scope.launch {
                            val received = withTimeoutOrNull(timeoutMs) { arDeferred.await() }
                            if (received == null) {
                                Timber.w("BleEngine: awaitReply timed out after ${timeoutMs}ms on $charUuid — continuing")
                            }
                            // Guard: if closeGatt() reset the fields during the wait, skip
                            // advancement so a stale coroutine does not corrupt commandIndex.
                            if (awaitReplyDeferred === arDeferred) {
                                awaitReplyDeferred = null
                                awaitReplyCharUuid = null
                                commandIndex++
                                executeNextSyncCommand()
                            }
                        }
                    }
                    // awaitEndOfStream path — hold until the armed strategy signals completion or timeout elapses.
                    aesDeferred != null -> {
                        val charUuid  = awaitEndOfStreamCharUuid
                        val timeoutMs = awaitEndOfStreamTimeoutMs
                        val strategy  = awaitEndOfStreamStrategy
                        val armedAtMs = awaitEndOfStreamArmedAtMs
                        val armedCommandIndex = commandIndex
                        scope.launch {
                            val received = withTimeoutOrNull(timeoutMs) {
                                coroutineScope { // races the deferred against a QuietPeriod poll loop, if armed
                                    if (strategy is EndOfStreamStrategy.QuietPeriod) {
                                        launch {
                                            val pollMs = (strategy.quietMs / 4).coerceAtLeast(100L)
                                            while (isActive) {
                                                delay(pollMs.milliseconds)
                                                val elapsed = SystemClock.elapsedRealtime() - awaitEndOfStreamArmedAtMs
                                                if (strategy.isQuietNowMs(elapsed)) {
                                                    aesDeferred.complete(Unit)
                                                    break
                                                }
                                            }
                                        }
                                    }
                                    aesDeferred.await()
                                }
                            }
                            val elapsedMs = SystemClock.elapsedRealtime() - armedAtMs
                            val reason = if (received != null) "MATCH" else "TIMEOUT"
                            Timber.tag(TAG).d(
                                "[EOS-RESOLVED] command=%d char=%s reason=%s elapsedMs=%d",
                                armedCommandIndex, charUuid, reason, elapsedMs
                            )
                            if (received == null) {
                                Timber.w("BleEngine: awaitEndOfStream timed out on $charUuid — continuing")
                            }
                            // Guard: skip advancement if closeGatt() reset the fields during the wait.
                            if (awaitEndOfStreamDeferred === aesDeferred) {
                                awaitEndOfStreamDeferred  = null
                                // deliberately NOT nulling awaitEndOfStreamCharUuid here
                                // (kept sticky across resolution) so a stray/duplicate notification
                                // arriving before the next command arms still hits the charUuid-match
                                // check in onCharacteristicChanged instead of skipping it entirely.
                                // awaitEndOfStreamStrategy == null (still cleared below) is what now
                                // signals "nothing currently armed" there.
                                awaitEndOfStreamStrategy  = null
                                awaitEndOfStreamArmedAtMs = 0L
                                endOfStreamReceived = true // triggers close-then-parse in executeNextSyncCommand
                                commandIndex++
                                executeNextSyncCommand()
                            }
                        }
                    }
                    else -> {
                        commandIndex++
                        executeNextSyncCommand()
                    }
                }
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

    // when buildSyncCommands is exported, WASM owns the full sequence.
    private suspend fun beginSyncCommandExecution() {
        val manifest = activeManifest ?: return
        val wasm = manifest.parsing as? ParsingConfig.WasmParsing
        val hasBuildSyncCommands = wasm?.exports?.buildSyncCommands != null
        Timber.tag(TAG).d("[SYNC] beginSyncCommandExecution: driver=%s hasBuildSyncCommands=%b exportName=%s",
            manifest.id, hasBuildSyncCommands, wasm?.exports?.buildSyncCommands)
        effectiveSyncCommands = if (hasBuildSyncCommands) {
            val cmds = resolveContextCommands(manifest)    // WASM output is the complete sequence
            Timber.tag(TAG).d("[SYNC] resolveContextCommands returned %d commands", cmds.size)
            cmds
        } else {
            manifest.syncCommands               // static list, used as-is
        }
        Timber.tag(TAG).d("[SYNC] effectiveSyncCommands count=%d", effectiveSyncCommands.size)
        executeNextSyncCommand()
    }

    // reads syncRequirements, builds the appropriate SyncContext (with or without a
    // DB fetch), and calls the driver's buildSyncCommands WASM export. Returns emptyList when
    // the export is absent or SyncContextFactory throws — the connection is never aborted.
    private suspend fun resolveContextCommands(
        manifest: WasmDriverManifest,
    ): List<SyncCommand> {
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

    // constructs a time-only SyncContext without hitting the DB.
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
        val commands = effectiveSyncCommands  // effectiveSyncCommands is always set correctly by beginSyncCommandExecution

        if (commandIndex >= commands.size) {
            Timber.d("BleEngine: sync commands done, device ready")
            Timber.tag(TAG).d("[SYNC] all %d commands complete endOfStreamReceived=%b", commands.size, endOfStreamReceived)
            // if awaitEndOfStream fired, close GATT immediately and parse the buffered
            // session. Otherwise fall through to the Connected + quiescence path (non-EOS drivers).
            if (endOfStreamReceived) {
                dispatchPostStreamParse()
                return
            }
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
                // hoisted above the await-arming blocks (pure computation) so the
                // awaitEndOfStream strategy can derive its opcode from the outgoing bytes.
                val bytes = parseHexBytes(cmd.bytes)
                // arm the await-reply deferred before issuing the write so a
                // notification that arrives before the write-ACK callback is not missed.
                val ar = cmd.awaitReply
                if (ar != null) {
                    val replyUuid = manifest.ble.characteristics[ar.characteristicRole]
                    if (replyUuid != null) {
                        awaitReplyDeferred  = CompletableDeferred()
                        awaitReplyCharUuid  = replyUuid
                        awaitReplyTimeoutMs = ar.timeoutMs
                    } else {
                        Timber.w("BleEngine: awaitReply role '${ar.characteristicRole}' not in manifest — treating as fire-and-forget")
                    }
                }
                // arm awaitEndOfStream deferred before the write so a fast terminal notification is not missed.
                val aes = cmd.awaitEndOfStream
                if (aes != null) {
                    val aesUuid = manifest.ble.characteristics[aes.characteristic]
                    if (aesUuid != null) {
                        // customInvoker bridges CUSTOM strategy instances to the currently
                        // loaded driver's WASM instance; every other strategy ignores this parameter.
                        val strategy = EndOfStreamStrategyFactory.from(aes) { wasmExport, notifBytes, notifOpcode, elapsedMs ->
                            driverRegistry.evaluateCustomPredicate(manifest.id, wasmExport, notifBytes, notifOpcode, elapsedMs)
                        }
                        val opcode = bytes.firstOrNull()?.toInt()?.and(0xFF) ?: 0
                        strategy.onStreamStart(opcode)
                        awaitEndOfStreamStrategy  = strategy
                        awaitEndOfStreamArmedAtMs = SystemClock.elapsedRealtime()
                        awaitEndOfStreamTimeoutMs = aes.timeoutMs
                        awaitEndOfStreamCharUuid  = aesUuid
                        awaitEndOfStreamDeferred  = CompletableDeferred()
                        // CHANGED: flush any quiescenceJob left running by an earlier
                        // non-EOS command in this manifest's command list — connectionState
                        // stays Connected throughout automatic sync execution, so a stale
                        // job would otherwise still fire mid-stream and prematurely drain
                        // affectedDates / enqueue DailySummaryWorker for partial data.
                        quiescenceJob?.cancel()
                        quiescenceJob = null
                    } else {
                        Timber.w("BleEngine: awaitEndOfStream characteristic '${aes.characteristic}' not in manifest — treating as fire-and-forget")
                    }
                }
                Timber.tag(TAG).d("[SYNC] write[%d] char=%s bytes=%s", commandIndex, charUuid, bytes.toHexString())
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
                // onCharacteristicWrite advances commandIndex (with await logic if deferred is set)
            }
            is SyncCommand.EnableNotify -> {
                val charUuid = manifest.ble.characteristics[cmd.characteristic] ?: run {
                    Timber.w("BleEngine: unknown characteristic role '${cmd.characteristic}'")
                    commandIndex++
                    executeNextSyncCommand()
                    return
                }
                Timber.tag(TAG).d("[SYNC] enable-notify[%d] char=%s", commandIndex, charUuid)
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
                Timber.tag(TAG).d("[SYNC] delay[%d] millis=%d", commandIndex, cmd.millis)
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
        // null the await-reply fields so any in-flight wait-coroutine's
        // deferred guard fails and commandIndex is not advanced after a reset.
        awaitReplyDeferred = null
        awaitReplyCharUuid = null
        // null awaitEndOfStream fields to invalidate any in-flight timeout coroutine.
        awaitEndOfStreamDeferred  = null
        awaitEndOfStreamCharUuid  = null
        awaitEndOfStreamStrategy  = null
        awaitEndOfStreamArmedAtMs = 0L
        awaitEndOfStreamTimeoutMs = 30_000L
        endOfStreamReceived       = false
        postStreamDisconnecting   = false
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
        val stepsMode = activeManifest?.stepsMode ?: StepsMode.DELTA // STEPS-MODE
        val caloriesMode = activeManifest?.caloriesMode ?: CaloriesMode.DELTA // CALORIES-MODE / METRIC-OWNERSHIP
        val syncIntervalMs = activeManifest?.syncIntervalMs // CHANGED
        metricRouter.route(reading, stepsMode, caloriesMode, syncIntervalMs) // STEPS-MODE / CALORIES-MODE / CHANGED
        val date = reading.recordedAt.atZone(ZoneId.systemDefault()).toLocalDate()
        synchronized(affectedDates) { affectedDates.add(date) }
    }

    // reverse-lookup characteristic UUID → manifest role name for SessionFrame.characteristic.
    // Returns the UUID string itself as a fallback if the manifest does not recognise it.
    private fun resolveCharacteristicRole(uuid: String): String =
        activeManifest?.ble?.characteristics
            ?.entries
            ?.firstOrNull { it.value.equals(uuid, ignoreCase = true) }
            ?.key ?: uuid

    // called when all sync commands are complete AND awaitEndOfStream has fired.
    // Closes the GATT connection synchronously (before parse), then on Dispatchers.IO:
    // parses the session cache, routes readings via MetricRouter, enqueues DailySummaryWorker,
    // finalises the sync session row, and transitions to SyncComplete.
    @SuppressLint("MissingPermission")
    private fun dispatchPostStreamParse() {
        val manifest = activeManifest ?: return
        val address  = activeDeviceAddress ?: return
        val capturedPacketCount = packetCount
        val preSyncSessionId    = currentSyncSessionId
        currentSyncSessionId = null

        // 1. Close GATT immediately — parse must not start until the link is closed.
        postStreamDisconnecting   = true
        endOfStreamReceived       = false
        awaitReplyDeferred        = null
        awaitReplyCharUuid        = null
        awaitEndOfStreamDeferred  = null
        awaitEndOfStreamCharUuid  = null
        awaitEndOfStreamStrategy  = null
        awaitEndOfStreamArmedAtMs = 0L
        awaitEndOfStreamTimeoutMs = 30_000L
        activeGatt?.disconnect()
        activeGatt?.close()
        activeGatt = null

        val frames = synchronized(sessionCache) { sessionCache.toList() }

        // 2. Log cache complete before parse begins.
        Timber.tag(TAG).d("session-cache-complete: frames=%d", frames.size)

        // 3–6. Parse on IO, route readings, enqueue workers, transition state.
        scope.launch(Dispatchers.IO) {
            val result = driverRegistry.parseSession(manifest, frames)
            val batteryReadings = mutableListOf<MetricReading>()

            when (result) {
                is WasmParseResult.Success -> {
                    val readingResults = validator.validateReadings(result.readings)
                    for (vr in readingResults) {
                        when (vr) {
                            is ValidationResult.Accepted -> {
                                routeReading(vr.item)
                                if (vr.item.metricType == MetricType.BATTERY) {
                                    batteryReadings.add(vr.item)
                                }
                            }
                            is ValidationResult.Rejected ->
                                Timber.w("dispatchPostStreamParse: dropped %s — %s",
                                    vr.item.metricType, vr.reason)
                        }
                    }
                    Timber.tag(TAG).d("parse-complete: readings=%d", result.readings.size)
                }
                is WasmParseResult.Empty ->
                    Timber.tag(TAG).d("parse-session: empty — no readings produced")
                is WasmParseResult.WasmTrap ->
                    Timber.tag(TAG).e("parse-session: wasm trap message=%s", result.message)
                is WasmParseResult.DeserialisationError ->
                    Timber.tag(TAG).e("parse-session: deserialisation error message=%s", result.message)
                is WasmParseResult.EngineNotInitialised ->
                    Timber.tag(TAG).e("parse-session: engine not initialised")
            }

            // 6. Clear cache regardless of parse result.
            synchronized(sessionCache) { sessionCache.clear() }

            // Drain affectedDates now; the actual worker enqueue is deferred to the
            // finally block below so it always runs after syncProcessor.process() (and
            // therefore sleepStagePromoter.promote()) has completed — matching triggerSync().
            val datesToProcess = synchronized(affectedDates) {
                affectedDates.toSet().also { affectedDates.clear() }
            }

            // Finalise sync session row and transition UI state.
            val syncResult = DriverSyncResult(
                deviceId        = address,
                driverId        = manifest.id,
                syncStartedAt   = syncStartedAt,
                syncEndedAt     = Instant.now(),
                metricReadings  = batteryReadings, // others already written by routeReading
                sleepSessions   = emptyList(), // sleep metrics arrive as MetricReading via parseSession
                activities      = emptyList(), // activity metrics arrive as MetricReading via parseSession
                rawPayloads     = emptyList(), // packets already persisted on arrival
                packetsReceived = capturedPacketCount,
            )
            try {
                val summary = syncProcessor.process(syncResult, preSyncSessionId = preSyncSessionId)
                withContext(Dispatchers.Main) {
                    _connectionState.value = BleConnectionState.SyncComplete(summary, address)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _connectionState.value = BleConnectionState.Error(e.message ?: "Sync failed")
                }
            } finally {
                datesToProcess.forEach { date ->
                    Timber.tag(TAG).d("[STAGE-6 DB-WRITE] enqueuing DailySummaryWorker for date=%s stepsMode=%s",
                        date, manifest.stepsMode)
                    enqueueSummaryWorker(date, workManager, manifest.stepsMode)
                }
                postStreamDisconnecting = false
            }
        }
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

    private fun ByteArray.toHexString(): String =
        joinToString(" ") { "%02X".format(it) }

    private fun ByteArray.toDptHexString(): String = // DPT
        joinToString(" ") { "0x%02X".format(it) }    // DPT
}
