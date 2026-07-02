package com.athletedata.openAthleteMetrics.ble.wasm

import com.athletedata.openAthleteMetrics.ble.SyncContext
import com.athletedata.openAthleteMetrics.ble.SyncContextSerializer
import com.athletedata.openAthleteMetrics.ble.driver.ParsingConfig
import com.athletedata.openAthleteMetrics.ble.driver.SyncCommand
import com.athletedata.openAthleteMetrics.ble.driver.WasmDriverManifest
import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.data.model.MetricReading
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasm.ChicoryException
import com.dylibso.chicory.wasm.Parser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.time.Instant
import java.util.Base64
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
 *   Raw BLE characteristic bytes (legacy) or JSON-encoded input (parseSession / buildSyncCommands)
 *
 * Offset 0x1000 (4096) OUTPUT REGION    ≤ 61,440 bytes
 *   WASM writes UTF-8 JSON; returns byte count as i32
 * ```
 *
 * Spec v1 drivers (specVersion = "1") receive BLE bytes at offset 0 with no metadata header.
 *
 * ## Driver author contract
 * - Module must declare `(memory (export "memory") 1)` (at least 1 page = 65,536 bytes).
 * - All export functions share the signature `(func (param i32 i32) (result i32))`.
 * - Write JSON output at byte offset 4,096; return the number of bytes written.
 * - Return 0 to signal "no data" for this payload.
 */
@Singleton
class WasmDriverEngine @Inject constructor(
    private val syncContextSerializer: SyncContextSerializer,
) {

    private class WasmTrapException(message: String) : Exception(message) // WASM-RESULT

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
     * Parses a complete buffered sync session by passing all [frames] to the WASM parseSession export.
     * Returns [WasmParseResult.EngineNotInitialised] if the driver is not loaded or lacks a parseSession export.
     */
    suspend fun parseSession(frames: List<SessionFrame>): WasmParseResult {
        Timber.tag(TAG).d("parseSession: frames=%d", frames.size)
        val framesJson = buildFramesJson(frames)
        Timber.tag(TAG).d("parse-session input (first 500): %s", framesJson.take(500))
        val framesBytes = framesJson.toByteArray(Charsets.UTF_8)

        return parseMutex.withLock {
            val wasm = loadedManifest?.parsing as? ParsingConfig.WasmParsing
                ?: return@withLock WasmParseResult.EngineNotInitialised
            val exportName = wasm.exports.parseSession
                ?: return@withLock WasmParseResult.EngineNotInitialised
            if (instance == null) return@withLock WasmParseResult.EngineNotInitialised

            // hume_band.wat's static lookup tables (scan patterns, base64 table, string
            // constants) live on their own memory page (0x40000+), well clear of the input
            // region below OUT_OFFSET, so oversized input no longer risks corrupting them.
            // Chunking is still worth keeping as a conservative bound on JSON payload size
            // and per-call parse-loop work for very large sessions.
            val chunks = splitFrameChunks(frames, framesBytes)
            Timber.tag(TAG).d("parseSession: chunks=%d totalBytes=%d", chunks.size, framesBytes.size)

            try {
                val manifest = loadedManifest!!
                val now = Instant.now()
                val allReadings = mutableListOf<MetricReading>()

                for (chunkBytes in chunks) {
                    val jsonStr = callParseSessionBytes(exportName, chunkBytes) ?: continue
                    if (jsonStr == "[]") continue
                    try {
                        json.decodeFromString<List<MetricWasmDto>>(jsonStr).mapTo(allReadings) { dto ->
                            MetricReading(
                                metricType = dto.metricType,
                                value = dto.value,
                                unit = dto.unit,
                                recordedAt = Instant.ofEpochMilli(dto.recordedAtMs),
                                createdAt = now,
                                source = DataSource.DEVICE,
                                driverId = manifest.id,
                                confidence = dto.confidence,
                                metaJson = dto.metaJson,
                            )
                        }
                    } catch (e: Exception) {
                        return@withLock WasmParseResult.DeserialisationError(e.message ?: "unknown")
                    }
                }

                Timber.tag(TAG).d("parseSession: readings=%d", allReadings.size)
                if (allReadings.isEmpty()) WasmParseResult.Empty else WasmParseResult.Success(allReadings)
            } catch (e: WasmTrapException) {
                WasmParseResult.WasmTrap(e.message ?: "unknown trap")
            }
        }
    }

    /**
     * Splits [frames] into chunks whose JSON UTF-8 encoding stays within [WASM_SAFE_INPUT_BYTES].
     * When the full session fits, returns a single-element list containing [fullBytes] directly
     * to avoid rebuilding the JSON.
     */
    private fun splitFrameChunks(frames: List<SessionFrame>, fullBytes: ByteArray): List<ByteArray> {
        if (fullBytes.size <= WASM_SAFE_INPUT_BYTES) return listOf(fullBytes)
        val n = (fullBytes.size + WASM_SAFE_INPUT_BYTES - 1) / WASM_SAFE_INPUT_BYTES
        val chunkSize = (frames.size + n - 1) / n
        return frames.chunked(chunkSize).map { chunk ->
            buildFramesJson(chunk).toByteArray(Charsets.UTF_8)
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
                    "WasmDriverEngine: driver %s uses spec v1 layout — " +
                    "metadata not available. Update to specVersion 2 for " +
                    "syncStartMs and utcOffsetMinutes support.",
                    manifest.id
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
            }.onFailure { ex ->
                Timber.e(ex, "WasmDriverEngine: re-instantiation failed after trap — clearing dead instance")
                instance = null
            }
            throw WasmTrapException(e.message ?: "unknown trap") // WASM-RESULT
        } }
    }

    /**
     * Writes [data] (JSON bytes) at [IN_OFFSET_V2], calls [functionName] with (offset, len),
     * and returns the UTF-8 JSON string the WASM wrote at [OUT_OFFSET].
     * Used for exports that receive JSON input rather than raw BLE bytes.
     */
    private suspend fun callParseSessionBytes(functionName: String, data: ByteArray): String? {
        val inst = instance ?: return null
        return withContext(Dispatchers.Default) {
            try {
                val memory = inst.memory()
                Timber.tag(TAG).d("[WASM-INPUT] json bytes length=${data.size}")
                memory.write(IN_OFFSET_V2, data)
                val stale = previousPacketSize - data.size
                if (stale > 0) memory.write(IN_OFFSET_V2 + data.size, ByteArray(stale))
                previousPacketSize = data.size
                val readback = memory.readBytes(IN_OFFSET_V2, minOf(data.size, 200))
                Timber.tag(TAG).d("[WASM-READBACK] first 200 bytes as string: ${readback.toString(Charsets.UTF_8)}")
                val result = inst.export(functionName).apply(IN_OFFSET_V2.toLong(), data.size.toLong())
                val outLen = result[0].toInt()
                Timber.tag(TAG).d("[WASM-RETURN] returnValue=$outLen inputPtr=$IN_OFFSET_V2 inputLen=${data.size}")
                if (outLen <= 0) null else {
                    val outputString = memory.readString(OUT_OFFSET, outLen)
                    Timber.tag(TAG).d("[WASM-OUTPUT] first 200 chars: ${outputString.take(200)}")
                    outputString
                }
            } catch (e: ChicoryException) {
                Timber.w(e, "WasmDriverEngine: ChicoryException in $functionName — re-instantiating")
                runCatching {
                    val wasm = loadedManifest?.parsing as? ParsingConfig.WasmParsing
                    if (wasm != null) instance = instantiate(wasm.wasmBytes)
                }.onFailure { ex ->
                    Timber.e(ex, "WasmDriverEngine: re-instantiation failed after trap — clearing dead instance")
                    instance = null
                }
                throw WasmTrapException(e.message ?: "unknown trap")
            }
        }
    }

    // CHANGED: context-aware variant — serializes SyncContext JSON and passes offset+len to WASM.
    // The WASM export receives the same (param i32 i32) signature as parse functions.
    // Writes: metadata block at offset 0, SyncContext JSON at IN_OFFSET_V2 (16).
    // Reads output from CMD_OUT_OFFSET (0x400). Returns emptyList on any failure.
    suspend fun buildSyncCommands(
        manifest: WasmDriverManifest,
        context: SyncContext,
    ): List<SyncCommand> {
        val wasm = manifest.parsing as? ParsingConfig.WasmParsing ?: return emptyList()
        val exportName = wasm.exports.buildSyncCommands ?: return emptyList()

        val loaded = parseMutex.withLock {
            if (loadedManifest?.id != manifest.id) {
                unload()
                try {
                    instance = instantiate(wasm.wasmBytes)
                    loadedManifest = manifest
                    true
                } catch (e: Exception) {
                    Timber.w(e, "WasmDriverEngine: failed to load driver ${manifest.id} for buildSyncCommands")
                    false
                }
            } else {
                true
            }
        }
        if (!loaded) return emptyList()

        return callBuildSyncCommandsWithContext(exportName, context) ?: emptyList()
    }

    // CHANGED: writes metadata + SyncContext JSON to WASM memory, calls export with (offset, len).
    private suspend fun callBuildSyncCommandsWithContext(
        functionName: String,
        context: SyncContext,
    ): List<SyncCommand>? = parseMutex.withLock {
        val inst = instance ?: return@withLock null
        withContext(Dispatchers.Default) {
            try {
                val currentTimeMs = Instant.now().toEpochMilli()
                val offsetMinutes = utcOffsetMinutes()
                val contextJson = syncContextSerializer.toJson(context)
                val contextBytes = contextJson.toByteArray(Charsets.UTF_8)

                val memory = inst.memory()
                memory.write(0, longToLeBytes(currentTimeMs))
                memory.write(8, shortToLeBytes(offsetMinutes))
                memory.write(10, ByteArray(6))                      // reserved, zeroed
                memory.write(IN_OFFSET_V2, contextBytes)
                // Zero stale bytes from a previous (longer) context write
                val stale = previousPacketSize - contextBytes.size
                if (stale > 0) memory.write(IN_OFFSET_V2 + contextBytes.size, ByteArray(stale))

                val exportFn = try {
                    inst.export(functionName)
                } catch (e: Exception) {
                    Timber.tag(TAG).e("buildSyncCommands export '%s' not found in WASM instance: %s", functionName, e.message)
                    return@withContext null
                }
                Timber.tag(TAG).d("buildSyncCommands export '%s' found — calling with inputOffset=%d len=%d",
                    functionName, IN_OFFSET_V2, contextBytes.size)
                val result = exportFn.apply(IN_OFFSET_V2.toLong(), contextBytes.size.toLong())
                val outLen = result[0].toInt()
                Timber.tag(TAG).d("buildSyncCommands: outLen=%d", outLen)
                if (outLen <= 0) return@withContext null

                val jsonStr = memory.readString(CMD_OUT_OFFSET, outLen)
                json.decodeFromString<List<SyncCommand>>(jsonStr)
            } catch (e: ChicoryException) {
                Timber.w(e, "WasmDriverEngine: ChicoryException in $functionName — re-instantiating")
                runCatching {
                    val wasm = loadedManifest?.parsing as? ParsingConfig.WasmParsing
                    if (wasm != null) instance = instantiate(wasm.wasmBytes)
                }.onFailure { ex ->
                    Timber.e(ex, "WasmDriverEngine: re-instantiation failed after trap — clearing dead instance")
                    instance = null
                }
                null
            } catch (e: Exception) {
                Timber.w(e, "WasmDriverEngine: $functionName returned malformed JSON — returning emptyList")
                null
            }
        }
    }

    /** Serialises [frames] to a JSON array string for passing to the WASM parseSession export. */
    private fun buildFramesJson(frames: List<SessionFrame>): String {
        val arr = JSONArray()
        for (frame in frames) {
            val obj = JSONObject()
            obj.put("characteristic", frame.characteristic)
            obj.put("opcode", frame.opcode)
            obj.put("bytes", Base64.getEncoder().encodeToString(frame.bytes))
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun ByteArray.toDptHexString(): String = // DPT
        joinToString(" ") { "0x%02X".format(it) }    // DPT

    internal fun longToLeBytes(value: Long): ByteArray =
        ByteArray(8) { i -> (value shr (i * 8)).toByte() }

    internal fun shortToLeBytes(value: Short): ByteArray {
        val v = value.toInt()
        return byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    }

    private fun utcOffsetMinutes(): Short =
        (TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000).toShort()

    companion object {
        private const val TAG = "data-pathway-tracker" // DPT
        private const val IN_OFFSET_V1 = 0   // legacy: spec v1 drivers read BLE bytes from offset 0
        private const val IN_OFFSET_V2 = 16  // spec v2: bytes 0–15 are the metadata header
        private const val OUT_OFFSET = 0x1000
        private const val CMD_OUT_OFFSET = 0x400  // 1024 — output offset for buildSyncCommands
        // hume_band.wat's static data used to start at 0xD800 (55,296), giving a hard
        // 55,280-byte input limit before it collided with those tables; they've since moved
        // to their own page (0x40000+). 50,000 is kept as a conservative chunk size to bound
        // per-call JSON payload size and parse-loop work, not because of that old collision.
        private const val WASM_SAFE_INPUT_BYTES = 50_000
        private val json = Json { ignoreUnknownKeys = true }
    }
}
