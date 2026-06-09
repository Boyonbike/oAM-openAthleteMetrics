package com.athletedata.openAthleteMetrics.ble.driver

import com.athletedata.openAthleteMetrics.ble.wasm.WasmDriverEngine
import com.athletedata.openAthleteMetrics.data.model.Activity
import com.athletedata.openAthleteMetrics.data.model.MetricReading
import com.athletedata.openAthleteMetrics.data.model.SleepSession
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DriverRegistry @Inject constructor(
    private val driverStorage: DriverStorage,
    private val wasmEngine: WasmDriverEngine,
) {
    private val _drivers = CopyOnWriteArrayList<WasmDriverManifest>()
    private var wasmLoadedId: String? = null
    private val _failedDriverIds = mutableSetOf<String>()

    fun initialiseDrivers() {
        driverStorage.loadAllDrivers().forEach { register(it) }
        Timber.d("DriverRegistry: loaded ${_drivers.size} driver(s)")
    }

    fun register(manifest: WasmDriverManifest) {
        _drivers.removeIf { it.id == manifest.id }
        _drivers.add(manifest)
        _failedDriverIds.remove(manifest.id)
        if (wasmLoadedId == manifest.id) {
            wasmEngine.unload()
            wasmLoadedId = null
        }
    }

    fun unregister(driverId: String) {
        _drivers.removeIf { it.id == driverId }
        if (wasmLoadedId == driverId) {
            wasmEngine.unload()
            wasmLoadedId = null
        }
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

    fun parseMetrics(
        manifest: WasmDriverManifest,
        characteristicUuid: String,
        data: ByteArray,
    ): List<MetricReading> {
        if (!ensureWasmLoaded(manifest)) return emptyList()
        return wasmEngine.parseMetrics(characteristicUuid, data, manifest.id)
    }

    fun parseSleep(
        manifest: WasmDriverManifest,
        characteristicUuid: String,
        data: ByteArray,
    ): SleepSession? {
        if (!ensureWasmLoaded(manifest)) return null
        return wasmEngine.parseSleep(characteristicUuid, data, manifest.id)
    }

    fun parseActivity(
        manifest: WasmDriverManifest,
        characteristicUuid: String,
        data: ByteArray,
    ): Activity? {
        if (!ensureWasmLoaded(manifest)) return null
        return wasmEngine.parseActivity(characteristicUuid, data, manifest.id)
    }

    private fun ensureWasmLoaded(manifest: WasmDriverManifest): Boolean {
        if (wasmLoadedId == manifest.id) return true
        if (manifest.id in _failedDriverIds) return false
        return if (wasmEngine.load(manifest)) {
            wasmLoadedId = manifest.id
            true
        } else {
            Timber.e("DriverRegistry: WASM load failed for driver '${manifest.id}'")
            _failedDriverIds.add(manifest.id)
            false
        }
    }
}
