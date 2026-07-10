package com.athletedata.openAthleteMetrics.ble.driver

import com.dylibso.chicory.wasm.Parser
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

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

        when (val parsing = manifest.parsing) {
            is ParsingConfig.WasmParsing -> {
                if (parsing.wasmBytes.size < WASM_MAGIC.size ||
                    !parsing.wasmBytes.copyOf(WASM_MAGIC.size).contentEquals(WASM_MAGIC)
                ) {
                    errors += "parsing.wasmBase64 does not decode to a valid WASM binary " +
                        "(expected magic header 0x00 0x61 0x73 0x6D)"
                }

                if (parsing.exports.parseMetrics.isNullOrBlank() && parsing.exports.parseSession.isNullOrBlank()) {
                    errors += "parsing.exports.p2arseMetrics must not be blank (or provide parseSession for buffered drivers)"
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

        return errors
    }

    companion object {
        private val SEMVER = Regex("""^\d+\.\d+\.\d+$""")
        private val WASM_MAGIC = byteArrayOf(0x00, 0x61, 0x73, 0x6D.toByte())
    }
}
