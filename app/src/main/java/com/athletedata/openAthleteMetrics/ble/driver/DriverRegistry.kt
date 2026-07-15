package com.athletedata.openAthleteMetrics.ble.driver

import com.athletedata.openAthleteMetrics.ble.SyncContext
import com.athletedata.openAthleteMetrics.ble.wasm.SessionFrame
import com.athletedata.openAthleteMetrics.ble.wasm.WasmDriverEngine
import com.athletedata.openAthleteMetrics.ble.wasm.WasmParseResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DriverRegistry @Inject constructor(
    private val driverStorage: DriverStorage,
    private val wasmEngine: WasmDriverEngine,
) {
    private val _drivers = CopyOnWriteArrayList<WasmDriverManifest>()
    @Volatile private var wasmLoadedId: String? = null
    private val _failedDriverIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val wasmMutex = Mutex()

    suspend fun initialiseDrivers() {
        driverStorage.loadAllDrivers().forEach { register(it) }
        Timber.d("DriverRegistry: loaded ${_drivers.size} driver(s)")
    }

    // CHANGED: returns cross-driver signature collision warnings (empty = no collision).
    // Non-blocking — a colliding manifest is still registered — but no longer silent.
    suspend fun register(manifest: WasmDriverManifest): List<String> {
        val warnings = collisionWarnings(manifest) // CHANGED
        _drivers.removeIf { it.id == manifest.id }
        _drivers.add(manifest)
        _failedDriverIds.remove(manifest.id)
        wasmMutex.withLock {
            if (wasmLoadedId == manifest.id) {
                wasmEngine.unload()
                wasmLoadedId = null
            }
        }
        // CHANGED: log even on the bootstrap path (initialiseDrivers), which has no UI
        // listener — surfaced via Timber there, and via the returned list for callers
        // (e.g. DevicesViewModel) that can forward it to the Devices UI.
        warnings.forEach { Timber.w("DriverRegistry: $it") }
        return warnings
    }

    // CHANGED: cross-driver check — compares manifest's signature set against every other
    // currently registered driver's signature set. See DriverCommandSignature/
    // commandSignatures() in ManifestValidator.kt for the shared signature model. Overlap is
    // keyed on (characteristicUuid, opcode) rather than full-tuple equality: two drivers
    // arming the *same* physical characteristic+opcode is the actual collision risk,
    // regardless of whether they happen to declare the same or different strategy types —
    // mirroring ManifestValidator's own intra-driver check, which flags this same slot when
    // it sees differing strategies within a single manifest.
    private fun collisionWarnings(manifest: WasmDriverManifest): List<String> {
        val incomingByKey = manifest.commandSignatures().groupBy { it.characteristicUuid to it.opcode }
        if (incomingByKey.isEmpty()) return emptyList()
        return _drivers.filter { it.id != manifest.id }.flatMap { existing ->
            val existingByKey = existing.commandSignatures().groupBy { it.characteristicUuid to it.opcode }
            incomingByKey.keys.intersect(existingByKey.keys).map { (charUuid, opcode) ->
                val incomingStrategies = incomingByKey.getValue(charUuid to opcode).map { it.strategyType }.distinct()
                val existingStrategies = existingByKey.getValue(charUuid to opcode).map { it.strategyType }.distinct()
                "Driver '${manifest.id}' collides with already-registered driver '${existing.id}': " +
                    "characteristic '$charUuid' opcode 0x${opcode.toString(16).padStart(2, '0')} " +
                    "(strategies $incomingStrategies vs $existingStrategies)"
            }
        }
    }

    suspend fun unregister(driverId: String) {
        _drivers.removeIf { it.id == driverId }
        wasmMutex.withLock {
            if (wasmLoadedId == driverId) {
                wasmEngine.unload()
                wasmLoadedId = null
            }
        }
    }

    fun startSync() {
        wasmEngine.startSync()
    }

    // context-aware variant; delegates to WasmDriverEngine.buildSyncCommands(context).
    suspend fun buildSyncCommands(
        manifest: WasmDriverManifest,
        context: SyncContext,
    ): List<SyncCommand> {
        if (!ensureWasmLoaded(manifest)) return emptyList()
        return wasmEngine.buildSyncCommands(manifest, context)
    }

    fun allDrivers(): List<WasmDriverManifest> = _drivers.toList()

    fun isWasmLoaded(manifest: WasmDriverManifest): Boolean = wasmLoadedId == manifest.id

    /**
     * Synchronous bridge for [EndOfStreamStrategy.Custom] — called directly from the BLE
     * notification callback (not a coroutine), so this must never suspend. Returns false
     * (never throws) if [manifestId]'s driver isn't currently the loaded one; delegates
     * everything else, including all failure handling, to [WasmDriverEngine.evaluateCustomPredicate].
     */
    fun evaluateCustomPredicate(
        manifestId: String,
        wasmExport: String,
        bytes: ByteArray,
        opcode: Int,
        elapsedMs: Long,
    ): Boolean {
        if (wasmLoadedId != manifestId) return false
        return wasmEngine.evaluateCustomPredicate(manifestId, wasmExport, bytes, opcode, elapsedMs)
    }

    fun resolve(
        deviceName: String?,
        serviceUuids: List<String>,
    ): Pair<WasmDriverManifest, MatchConfidence>? {
        fun WasmDriverManifest.matches(): Boolean {
            val nameMatch = ble.matchByName != null && deviceName != null &&
                deviceName.startsWith(ble.matchByName)
            val uuidMatch = ble.matchByServiceUuid != null &&
                serviceUuids.any { it.equals(ble.matchByServiceUuid, ignoreCase = true) }
            return if (ble.matchByName != null && ble.matchByServiceUuid != null) {
                nameMatch && uuidMatch
            } else {
                nameMatch || uuidMatch
            }
        }
        return _drivers.firstOrNull { it.ble.matchConfidence == MatchConfidence.CERTAIN && it.matches() }
            ?.let { it to MatchConfidence.CERTAIN }
            ?: _drivers.firstOrNull { it.matches() }?.let { it to MatchConfidence.PROBABLE }
    }

    /** Delegates bulk session parsing to [WasmDriverEngine.parseSession]. */
    suspend fun parseSession(
        manifest: WasmDriverManifest,
        frames: List<SessionFrame>,
    ): WasmParseResult {
        if (!ensureWasmLoaded(manifest)) return WasmParseResult.EngineNotInitialised
        return wasmEngine.parseSession(frames)
    }

    private suspend fun ensureWasmLoaded(manifest: WasmDriverManifest): Boolean =
        wasmMutex.withLock {
            if (wasmLoadedId == manifest.id) return@withLock true
            if (manifest.id in _failedDriverIds) return@withLock false
            if (wasmEngine.load(manifest)) {
                wasmLoadedId = manifest.id
                true
            } else {
                Timber.e("DriverRegistry: WASM load failed for driver '${manifest.id}'")
                _failedDriverIds.add(manifest.id)
                false
            }
        }
}
