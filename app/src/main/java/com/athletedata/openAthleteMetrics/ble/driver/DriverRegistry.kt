package com.athletedata.openAthleteMetrics.ble.driver

import com.athletedata.openAthleteMetrics.ble.wasm.JsonDriverEngine
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
    private val jsonEngine: JsonDriverEngine,
) {
    private val _drivers = CopyOnWriteArrayList<WasmDriverManifest>()
    private var wasmLoadedId: String? = null

    fun initialiseDrivers() {
        driverStorage.loadAllDrivers().forEach { register(it) }
        Timber.d("DriverRegistry: loaded ${_drivers.size} driver(s)")
    }

    fun register(manifest: WasmDriverManifest) {
        _drivers.removeIf { it.id == manifest.id }
        _drivers.add(manifest)
    }

    fun unregister(driverId: String) {
        _drivers.removeIf { it.id == driverId }
        if (wasmLoadedId == driverId) {
            wasmEngine.unload()
            wasmLoadedId = null
        }
    }

    fun allDrivers(): List<WasmDriverManifest> = _drivers.toList()

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
    ): List<MetricReading> = when (val parsing = manifest.parsing) {
        is ParsingConfig.JsonParsing ->
            jsonEngine.parseMetrics(characteristicUuid, data, parsing.rules, manifest.id)
        is ParsingConfig.WasmParsing -> {
            ensureWasmLoaded(manifest)
            wasmEngine.parseMetrics(characteristicUuid, data, manifest.id)
        }
    }

    fun parseSleep(
        manifest: WasmDriverManifest,
        characteristicUuid: String,
        data: ByteArray,
    ): SleepSession? = when (manifest.parsing) {
        is ParsingConfig.WasmParsing -> {
            ensureWasmLoaded(manifest)
            wasmEngine.parseSleep(characteristicUuid, data, manifest.id)
        }
        else -> null
    }

    fun parseActivity(
        manifest: WasmDriverManifest,
        characteristicUuid: String,
        data: ByteArray,
    ): Activity? = when (manifest.parsing) {
        is ParsingConfig.WasmParsing -> {
            ensureWasmLoaded(manifest)
            wasmEngine.parseActivity(characteristicUuid, data, manifest.id)
        }
        else -> null
    }

    private fun ensureWasmLoaded(manifest: WasmDriverManifest) {
        if (wasmLoadedId != manifest.id) {
            wasmEngine.load(manifest)
            wasmLoadedId = manifest.id
        }
    }
}
