package com.athletedata.openAthleteMetrics.ble.wasm

import com.athletedata.openAthleteMetrics.ble.driver.ParsingConfig
import com.athletedata.openAthleteMetrics.ble.driver.WasmDriverManifest
import com.athletedata.openAthleteMetrics.ble.driver.WasmExports
import com.athletedata.openAthleteMetrics.data.model.Activity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.data.model.MetricReading
import com.athletedata.openAthleteMetrics.data.model.SleepSession
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasm.ChicoryException
import com.dylibso.chicory.wasm.Parser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.json.JSONArray
import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages one live Chicory WASM [Instance] for the currently active BLE driver.
 *
 * ## Memory layout (spec v2, specVersion = "2")
 *
 * ```
 * Offset 0x0000 (  0)  METADATA REGION  16 bytes
 *   Bytes 0–7:  syncStartMs      — i64 little-endian — UTC epoch ms when sync began
 *   Bytes 8–9:  utcOffsetMinutes — i16 little-endian — device timezone offset in minutes
 *   Bytes 10–15: reserved (zeroed)
 *
 * Offset 0x0010 ( 16)  INPUT REGION     ≤ 4,080 bytes
 *   Raw BLE characteristic bytes
 *
 * Offset 0x1000 (4096) OUTPUT REGION    ≤ 61,440 bytes
 *   WASM writes UTF-8 JSON; returns byte count as i32
 * ```
 *
 * Spec v1 drivers (specVersion = "1") receive BLE bytes at offset 0 with no metadata header.
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
    private val parseMutex = Mutex()

    private var syncStartMs: Long = 0L
    private var capturedUtcOffsetMinutes: Short = 0
    private var previousPacketSize: Int = 0

    /**
     * Compiles and instantiates the WASM binary from [manifest].
     * Replaces any previously loaded instance.
     * Returns false if compilation fails.
     */
    suspend fun load(manifest: WasmDriverManifest): Boolean {
        val wasm = manifest.parsing as? ParsingConfig.WasmParsing ?: return false
        return parseMutex.withLock {
            unload()
            try {
                instance = instantiate(wasm.wasmBytes)
                loadedManifest = manifest
                true
            } catch (e: Exception) {
                Timber.w(e, "WasmDriverEngine: failed to load driver ${manifest.id}")
                false
            }
        }
    }

    /** Records the sync start time and resets stale-byte tracking. Call once per sync session. */
    fun startSync() {
        syncStartMs = System.currentTimeMillis()
        capturedUtcOffsetMinutes = utcOffsetMinutes()
        previousPacketSize = 0
    }

    /**
     * Calls the WASM parseMetrics export with [data] and deserialises the result.
     * Returns an empty list on any error or missing export — never throws.
     */
    suspend fun parseMetrics(
        characteristicUuid: String,
        data: ByteArray,
        driverId: String,
    ): List<MetricReading> =
        callAndDecode(data, { it.parseMetrics }, "parseMetrics(driver=$driverId)") { jsonStr ->
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
        } ?: emptyList()

    /**
     * Calls the WASM parseSleep export if present.
     * Duration is computed from (sleepEndMs − sleepStartMs); the DTO's durationMinutes field is ignored.
     * Returns null if the export is absent, the payload has no data, or any error occurs.
     */
    suspend fun parseSleep(
        characteristicUuid: String,
        data: ByteArray,
        driverId: String,
    ): SleepSession? =
        callAndDecode(data, { it.parseSleep }, "parseSleep(driver=$driverId)") { jsonStr ->
            val dto = json.decodeFromString<SleepWasmDto>(jsonStr)
            val startInstant = Instant.ofEpochMilli(dto.sleepStartMs)
            // Extend end to cover stage blocks (e.g. AWAKE) that lie past the reported
            // sleepEndMs — some drivers truncate sleepEndMs to the last non-AWAKE stage.
            val endInstant = stageAwareEndInstant(dto.sleepEndMs, dto.stagesJson)
            SleepSession(
                date = LocalDate.parse(dto.dateIso),
                sleepStartMs = startInstant,
                sleepEndMs = endInstant,
                durationMinutes = ChronoUnit.MINUTES.between(startInstant, endInstant).toInt(),
                source = DataSource.DEVICE,
                driverId = driverId,
            )
        }

    /**
     * Calls the WASM parseActivity export if present.
     * Returns null if the export is absent, the payload has no data, or any error occurs.
     */
    suspend fun parseActivity(
        characteristicUuid: String,
        data: ByteArray,
        driverId: String,
    ): Activity? =
        callAndDecode(data, { it.parseActivity }, "parseActivity(driver=$driverId)") { jsonStr ->
            val dto = json.decodeFromString<ActivityWasmDto>(jsonStr)
            val derivedDuration = ((dto.endTimeMs - dto.startTimeMs) / 60_000L).toInt()
            if (derivedDuration <= 0) {
                Timber.w(
                    "WasmDriverEngine: Discarding activity ${dto.deviceName}: derived duration ≤ 0 " +
                    "startMs=${dto.startTimeMs} endMs=${dto.endTimeMs}"
                )
                null
            } else {
                Activity(
                    startTime = Instant.ofEpochMilli(dto.startTimeMs),
                    endTime = Instant.ofEpochMilli(dto.endTimeMs),
                    durationMinutes = derivedDuration,
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
            }
        }

    private suspend fun <T> callAndDecode(
        data: ByteArray,
        getExportName: (WasmExports) -> String?,
        logTag: String,
        decode: (String) -> T,
    ): T? = parseMutex.withLock {
        val wasm = loadedManifest?.parsing as? ParsingConfig.WasmParsing ?: return@withLock null
        val exportName = getExportName(wasm.exports) ?: return@withLock null
        try {
            val jsonStr = callParse(exportName, data)
                ?.takeIf { it.isNotBlank() && it != "{}" }
                ?: return@withLock null
            decode(jsonStr)
        } catch (e: Exception) {
            Timber.w(e, "WasmDriverEngine: $logTag failed")
            null
        }
    }

    /** Releases the WASM instance. Safe to call when nothing is loaded. */
    fun unload() {
        instance = null
        loadedManifest = null
        syncStartMs = 0L
        previousPacketSize = 0
    }

    private fun instantiate(wasmBytes: ByteArray): Instance {
        val module = Parser.parse(wasmBytes)
        return Instance.builder(module).build()
    }

    /**
     * Writes metadata and [data] into WASM memory, calls [functionName], and returns the
     * JSON string the WASM wrote at [OUT_OFFSET].
     *
     * For spec v2 drivers: writes the 16-byte metadata header (syncStartMs, utcOffsetMinutes,
     * reserved zeros) then the packet bytes at offset 16. Stale bytes from the previous
     * (longer) packet are zeroed. The WASM export is called with offset 16.
     *
     * For spec v1 drivers: writes bytes at offset 0 with no metadata header. A warning is
     * logged to prompt migration.
     *
     * Returns null when outLen == 0 ("no data") or on any [ChicoryException] — after a trap
     * the instance is re-instantiated.
     */
    private suspend fun callParse(functionName: String, data: ByteArray): String? {
        val inst = instance ?: return null
        val manifest = loadedManifest ?: return null
        val isV2 = manifest.specVersion == "2"
        val inputOffset = if (isV2) IN_OFFSET_V2 else IN_OFFSET_V1

        return withContext(Dispatchers.Default) { try {
            val memory = inst.memory()

            if (isV2) {
                memory.write(0, longToLeBytes(syncStartMs))
                memory.write(8, shortToLeBytes(capturedUtcOffsetMinutes))
                memory.write(10, ByteArray(6))                      // reserved, zeroed
                memory.write(IN_OFFSET_V2, data)
                val stale = previousPacketSize - data.size
                if (stale > 0) memory.write(IN_OFFSET_V2 + data.size, ByteArray(stale))
            } else {
                Timber.w(
                    "WasmDriverEngine: driver ${manifest.id} uses spec v1 layout — " +
                    "metadata not available. Update to specVersion 2 for " +
                    "syncStartMs and utcOffsetMinutes support."
                )
                memory.write(IN_OFFSET_V1, data)
            }
            previousPacketSize = data.size

            val result = inst.export(functionName).apply(inputOffset.toLong(), data.size.toLong())
            val outLen = result[0].toInt()
            if (outLen <= 0) null else memory.readString(OUT_OFFSET, outLen)
        } catch (e: ChicoryException) {
            Timber.w(e, "WasmDriverEngine: ChicoryException in $functionName — re-instantiating")
            runCatching {
                val wasm = loadedManifest?.parsing as? ParsingConfig.WasmParsing
                if (wasm != null) instance = instantiate(wasm.wasmBytes)
            }.onFailure { e ->
                Timber.e(e, "WasmDriverEngine: re-instantiation failed after trap — clearing dead instance")
                instance = null
            }
            null
        } }
    }

    private fun stageAwareEndInstant(reportedEndMs: Long, stagesJson: String?): Instant {
        if (stagesJson.isNullOrBlank()) return Instant.ofEpochMilli(reportedEndMs)
        val stageMaxEndMs = try {
            val arr = JSONArray(stagesJson)
            (0 until arr.length()).maxOfOrNull { arr.getJSONObject(it).getLong("endMs") }
        } catch (_: Exception) { null } ?: return Instant.ofEpochMilli(reportedEndMs)
        return Instant.ofEpochMilli(maxOf(reportedEndMs, stageMaxEndMs))
    }

    private fun longToLeBytes(value: Long): ByteArray =
        ByteArray(8) { i -> (value shr (i * 8)).toByte() }

    private fun shortToLeBytes(value: Short): ByteArray {
        val v = value.toInt()
        return byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    }

    private fun utcOffsetMinutes(): Short =
        (TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000).toShort()

    companion object {
        private const val IN_OFFSET_V1 = 0   // legacy: spec v1 drivers read BLE bytes from offset 0
        private const val IN_OFFSET_V2 = 16  // spec v2: bytes 0–15 are the metadata header
        private const val OUT_OFFSET = 0x1000
        private val json = Json { ignoreUnknownKeys = true }
    }
}
