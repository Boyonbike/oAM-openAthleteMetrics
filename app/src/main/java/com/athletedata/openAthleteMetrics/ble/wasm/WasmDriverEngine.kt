package com.athletedata.openAthleteMetrics.ble.wasm

import com.athletedata.openAthleteMetrics.ble.driver.ParsingConfig
import com.athletedata.openAthleteMetrics.ble.driver.WasmDriverManifest
import com.athletedata.openAthleteMetrics.data.model.Activity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.data.model.MetricReading
import com.athletedata.openAthleteMetrics.data.model.SleepSession
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasm.ChicoryException
import com.dylibso.chicory.wasm.Parser
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages one live Chicory WASM [Instance] for the currently active BLE driver.
 *
 * ## Memory layout (shared between Kotlin and WASM)
 *
 * ```
 * Offset 0x0000 (    0)  INPUT REGION   ≤ 4,096 bytes
 *   Kotlin writes raw BLE characteristic bytes here before every call.
 *
 * Offset 0x1000 (4,096)  OUTPUT REGION  ≤ 61,440 bytes
 *   WASM writes its UTF-8 JSON result here; returns byte count as i32.
 * ```
 *
 * ## Driver author contract
 * - Module must declare `(memory (export "memory") 1)` (at least 1 page = 65,536 bytes).
 * - All three export functions share the signature `(func (param i32 i32) (result i32))`.
 * - Write JSON output at byte offset 4,096; return the number of bytes written.
 * - Return 0 to signal "no data" for this payload.
 */
@Singleton
class WasmDriverEngine @Inject constructor() {

    private var instance: Instance? = null
    private var loadedManifest: WasmDriverManifest? = null

    /**
     * Compiles and instantiates the WASM binary from [manifest].
     * Replaces any previously loaded instance.
     * Returns false if compilation fails.
     */
    fun load(manifest: WasmDriverManifest): Boolean {
        val wasm = manifest.parsing as? ParsingConfig.WasmParsing ?: return false
        unload()
        return try {
            instance = instantiate(wasm.wasmBytes)
            loadedManifest = manifest
            true
        } catch (e: Exception) {
            Timber.w(e, "WasmDriverEngine: failed to load driver ${manifest.id}")
            false
        }
    }

    /**
     * Calls the WASM parseMetrics export with [data] and deserialises the result.
     * Returns an empty list on any error or missing export — never throws.
     */
    fun parseMetrics(
        characteristicUuid: String,
        data: ByteArray,
        driverId: String,
    ): List<MetricReading> {
        val wasm = loadedManifest?.parsing as? ParsingConfig.WasmParsing ?: return emptyList()
        return try {
            val jsonStr = callParse(wasm.exports.parseMetrics, data) ?: return emptyList()
            val now = Instant.now()
            json.decodeFromString<List<MetricWasmDto>>(jsonStr).map { dto ->
                MetricReading(
                    metricType = dto.metricType,
                    value = dto.value,
                    unit = dto.unit,
                    recordedAt = Instant.ofEpochMilli(dto.recordedAtMs),
                    createdAt = now,
                    source = DataSource.DEVICE,
                    driverId = driverId,
                    confidence = dto.confidence,
                    metaJson = dto.metaJson,
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "WasmDriverEngine: parseMetrics failed for driver $driverId")
            emptyList()
        }
    }

    /**
     * Calls the WASM parseSleep export if present.
     * Returns null if the export is absent, the payload has no data, or any error occurs.
     */
    fun parseSleep(
        characteristicUuid: String,
        data: ByteArray,
        driverId: String,
    ): SleepSession? {
        val wasm = loadedManifest?.parsing as? ParsingConfig.WasmParsing ?: return null
        val exportName = wasm.exports.parseSleep ?: return null
        return try {
            val jsonStr = callParse(exportName, data)
                ?.takeIf { it.isNotBlank() && it != "{}" }
                ?: return null
            val dto = json.decodeFromString<SleepWasmDto>(jsonStr)
            SleepSession(
                date = LocalDate.parse(dto.dateIso),
                sleepStartMs = Instant.ofEpochMilli(dto.sleepStartMs),
                sleepEndMs = Instant.ofEpochMilli(dto.sleepEndMs),
                durationMinutes = dto.durationMinutes,
                stagesJson = dto.stagesJson,
                source = DataSource.DEVICE,
                driverId = driverId,
            )
        } catch (e: Exception) {
            Timber.w(e, "WasmDriverEngine: parseSleep failed for driver $driverId")
            null
        }
    }

    /**
     * Calls the WASM parseActivity export if present.
     * Returns null if the export is absent, the payload has no data, or any error occurs.
     */
    fun parseActivity(
        characteristicUuid: String,
        data: ByteArray,
        driverId: String,
    ): Activity? {
        val wasm = loadedManifest?.parsing as? ParsingConfig.WasmParsing ?: return null
        val exportName = wasm.exports.parseActivity ?: return null
        return try {
            val jsonStr = callParse(exportName, data)
                ?.takeIf { it.isNotBlank() && it != "{}" }
                ?: return null
            val dto = json.decodeFromString<ActivityWasmDto>(jsonStr)
            Activity(
                startTime = Instant.ofEpochMilli(dto.startTimeMs),
                endTime = Instant.ofEpochMilli(dto.endTimeMs),
                durationMinutes = dto.durationMinutes,
                deviceName = dto.deviceName,
                source = DataSource.DEVICE,
                driverId = driverId,
                avgHrBpm = dto.avgHrBpm,
                maxHrBpm = dto.maxHrBpm,
                minHrBpm = dto.minHrBpm,
                calories = dto.calories,
                activeCalories = dto.activeCalories,
                distanceMeters = dto.distanceMeters,
                steps = dto.steps,
                hrZonesJson = dto.hrZonesJson,
            )
        } catch (e: Exception) {
            Timber.w(e, "WasmDriverEngine: parseActivity failed for driver $driverId")
            null
        }
    }

    /** Releases the WASM instance. Safe to call when nothing is loaded. */
    fun unload() {
        instance = null
        loadedManifest = null
    }

    private fun instantiate(wasmBytes: ByteArray): Instance {
        val module = Parser.parse(wasmBytes)
        return Instance.builder(module).build()
    }

    /**
     * Writes [data] into WASM memory at [IN_OFFSET], calls [functionName], and returns the
     * JSON string the WASM wrote at [OUT_OFFSET]. Returns null when outLen == 0 ("no data")
     * or on any [ChicoryException] — after a trap the instance is re-instantiated.
     */
    private fun callParse(functionName: String, data: ByteArray): String? {
        val inst = instance ?: return null
        return try {
            val memory = inst.memory()
            memory.write(IN_OFFSET, data)
            val result = inst.export(functionName).apply(IN_OFFSET.toLong(), data.size.toLong())
            val outLen = result[0].toInt()
            if (outLen <= 0) null else memory.readString(OUT_OFFSET, outLen)
        } catch (e: ChicoryException) {
            Timber.w(e, "WasmDriverEngine: ChicoryException in $functionName — re-instantiating")
            runCatching {
                val wasm = loadedManifest?.parsing as? ParsingConfig.WasmParsing
                if (wasm != null) instance = instantiate(wasm.wasmBytes)
            }
            null
        }
    }

    companion object {
        private const val IN_OFFSET = 0
        private const val OUT_OFFSET = 0x1000
        private val json = Json { ignoreUnknownKeys = true }
    }
}
