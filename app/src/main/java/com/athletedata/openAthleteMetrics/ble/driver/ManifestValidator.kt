package com.athletedata.openAthleteMetrics.ble.driver

import com.dylibso.chicory.wasm.Parser
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

// CHANGED: a driver's signature for collision-detection purposes — one tuple per
// statically-declared WRITE command that arms an EOS strategy. Shared by ManifestValidator's
// intra-driver check (below) and DriverRegistry's cross-driver check.
data class DriverCommandSignature(
    val characteristicUuid: String,
    val opcode: Int,
    val strategyType: String,
)

// CHANGED: pure, manifest-only derivation — no engine coupling, no I/O, no Timber. Scope
// note: only sees manifest.syncCommands as declared in JSON; commands built at runtime by a
// driver's buildSyncCommands WASM export are invisible here (same boundary the CUSTOM-
// wasmExport check below already documents for that reason).
fun WasmDriverManifest.commandSignatures(): List<DriverCommandSignature> =
    syncCommands.filterIsInstance<SyncCommand.Write>().mapNotNull { write ->
        val aes = write.awaitEndOfStream ?: return@mapNotNull null
        DriverCommandSignature(
            characteristicUuid = ble.characteristics[aes.characteristic] ?: aes.characteristic,
            opcode = firstOpcodeByte(write.bytes),
            // mirrors EndOfStreamStrategyFactory's null-defaults-to-FIXED_OFFSET_BYTE branch
            strategyType = aes.strategy ?: "FIXED_OFFSET_BYTE",
        )
    }

// CHANGED
private fun firstOpcodeByte(hex: String): Int =
    hex.trim().split("\\s+".toRegex()).firstOrNull { it.isNotEmpty() }
        ?.removePrefix("0x")?.removePrefix("0X")?.toIntOrNull(16) ?: 0

/**
 * Validates a fully-deserialised [WasmDriverManifest] before it is used by the runtime.
 *
 * All checks are pure — no I/O, no Android APIs, no coroutines.
 * Call [validate] immediately after deserialisation; proceed only when the result is empty.
 */
class ManifestValidator {

    /**
     * Returns a list of human-readable error strings describing every constraint violation.
     * An empty list means the manifest is valid.
     */
    fun validate(manifest: WasmDriverManifest): List<String> {
        val errors = mutableListOf<String>()

        if (manifest.id.isBlank()) {
            errors += "id must not be blank"
        }

        if (!SEMVER.matches(manifest.version)) {
            errors += "version '${manifest.version}' does not match semver (MAJOR.MINOR.PATCH)"
        }

        if (manifest.supportedMetrics.isEmpty()) {
            errors += "supportedMetrics must not be empty"
        }

        if (manifest.ble.services.isEmpty()) {
            errors += "ble.services must not be empty"
        }

        // CHANGED: syncIntervalMs feeds MetricRouter's plausibility-ceiling scaling; a
        // zero/negative value would collapse or invert that ceiling nonsensically.
        val syncIntervalMs = manifest.syncIntervalMs
        if (syncIntervalMs != null && syncIntervalMs <= 0) {
            errors += "syncIntervalMs must be positive if declared"
        }

        // CHANGED: sessionGapThresholdMs feeds SleepStagePromoter's session-grouping window; a
        // zero/negative value would collapse or invert grouping nonsensically.
        val sessionGapThresholdMs = manifest.sessionGapThresholdMs
        if (sessionGapThresholdMs != null && sessionGapThresholdMs <= 0) {
            errors += "sessionGapThresholdMs must be positive if declared"
        }

        when (val parsing = manifest.parsing) {
            is ParsingConfig.WasmParsing -> {
                if (parsing.wasmBytes.size < WASM_MAGIC.size ||
                    !parsing.wasmBytes.copyOf(WASM_MAGIC.size).contentEquals(WASM_MAGIC)
                ) {
                    errors += "parsing.wasmBase64 does not decode to a valid WASM binary " +
                        "(expected magic header 0x00 0x61 0x73 0x6D)"
                }

                if (parsing.exports.parseSession.isNullOrBlank()) {
                    errors += "parsing.exports.parseSession must not be blank — parseMetrics-only (per-packet) drivers are not currently supported by the sync pipeline"
                }

                // CUSTOM strategy: catch a typo'd wasmExport at load time rather than only
                // discovering it during a live sync. Only covers statically-declared
                // syncCommands — commands returned by buildSyncCommands are generated at
                // runtime and don't exist yet to check here.
                val customExports = manifest.syncCommands
                    .filterIsInstance<SyncCommand.Write>()
                    .mapNotNull { it.awaitEndOfStream }
                    .filter { it.strategy == "CUSTOM" }
                    .mapNotNull { it.params?.get("wasmExport")?.jsonPrimitive?.contentOrNull }
                    .distinct()
                if (customExports.isNotEmpty()) {
                    val moduleExportNames = try {
                        val module = Parser.parse(parsing.wasmBytes)
                        (0 until module.exportSection().exportCount())
                            .map { module.exportSection().getExport(it).name() }
                            .toSet()
                    } catch (e: Exception) {
                        null // wasmBytes already flagged invalid above; don't double-report
                    }
                    if (moduleExportNames != null) {
                        for (exportName in customExports) {
                            if (exportName !in moduleExportNames) {
                                errors += "awaitEndOfStream CUSTOM strategy references wasmExport " +
                                    "'$exportName' which is not exported by the WASM module"
                            }
                        }
                    }
                }
            }
        }

        // CHANGED: intra-driver signature collision check — reject a manifest whose own
        // declared commands arm two different, incompatible EOS strategies for the same
        // characteristic+opcode pair.
        val signatureGroups = manifest.commandSignatures().groupBy { it.characteristicUuid to it.opcode }
        for ((key, sigs) in signatureGroups) {
            val distinctStrategies = sigs.map { it.strategyType }.distinct()
            if (distinctStrategies.size > 1) {
                val (charUuid, opcode) = key
                errors += "conflicting end-of-stream strategies declared for characteristic " +
                    "'$charUuid' opcode 0x${opcode.toString(16).padStart(2, '0')}: " +
                    distinctStrategies.joinToString(" vs ")
            }
        }

        return errors
    }

    companion object {
        private val SEMVER = Regex("""^\d+\.\d+\.\d+$""")
        private val WASM_MAGIC = byteArrayOf(0x00, 0x61, 0x73, 0x6D.toByte())
    }
}
