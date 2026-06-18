package com.athletedata.openAthleteMetrics.ble.driver

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

                if (parsing.exports.parseMetrics.isBlank()) {
                    errors += "parsing.exports.parseMetrics must not be blank"
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
