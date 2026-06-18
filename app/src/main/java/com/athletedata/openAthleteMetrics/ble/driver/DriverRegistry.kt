package com.athletedata.openAthleteMetrics.ble.driver

import com.athletedata.openAthleteMetrics.ble.wasm.WasmDriverEngine
import com.athletedata.openAthleteMetrics.data.model.Activity
import com.athletedata.openAthleteMetrics.data.model.MetricReading
import com.athletedata.openAthleteMetrics.data.model.SleepSession
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

    suspend fun register(manifest: WasmDriverManifest) {
        _drivers.removeIf { it.id == manifest.id }
        _drivers.add(manifest)
        _failedDriverIds.remove(manifest.id)
        wasmMutex.withLock {
            if (wasmLoadedId == manifest.id) {
                wasmEngine.unload()
                wasmLoadedId = null
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

    fun allDrivers(): List<WasmDriverManifest> = _drivers.toList()

    fun isWasmLoaded(manifest: WasmDriverManifest): Boolean = wasmLoadedId == manifest.id

    fun resolve(
        deviceName: String?,
        serviceUuids: List<String>,
    ): Pair<WasmDriverManifest, MatchConfidence>? {
        fun WasmDriverManifest.matches(): Boolean {
            val nameMatch = ble.matchByName != null && deviceName != null &&
                deviceName.startsWith(ble.matchByName)
            val uuidMatch = ble.matchByServiceUuid != null &&
                serviceUuids.any { it.equals(ble.matchByServiceUuid, ignoreCase = true) }
            return nameMatch || uuidMatch
        }
        return _drivers.firstOrNull { it.ble.matchConfidence == MatchConfidence.CERTAIN && it.matches() }
            ?.let { it to MatchConfidence.CERTAIN }
            ?: _drivers.firstOrNull { it.matches() }?.let { it to MatchConfidence.PROBABLE }
    }

    suspend fun parseMetrics(
        manifest: WasmDriverManifest,
        characteristicUuid: String,
        data: ByteArray,
    ): List<MetricReading> {
        if (!ensureWasmLoaded(manifest)) return emptyList()
        return wasmEngine.parseMetrics(characteristicUuid, data, manifest.id)
    }

    suspend fun parseSleep(
        manifest: WasmDriverManifest,
        characteristicUuid: String,
        data: ByteArray,
    ): SleepSession? {
        if (!ensureWasmLoaded(manifest)) return null
        return wasmEngine.parseSleep(characteristicUuid, data, manifest.id)
    }

    suspend fun parseActivity(
        manifest: WasmDriverManifest,
        characteristicUuid: String,
        data: ByteArray,
    ): Activity? {
        if (!ensureWasmLoaded(manifest)) return null
        return wasmEngine.parseActivity(characteristicUuid, data, manifest.id)
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
