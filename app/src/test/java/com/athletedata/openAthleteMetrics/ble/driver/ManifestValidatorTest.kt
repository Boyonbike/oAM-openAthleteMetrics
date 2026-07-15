package com.athletedata.openAthleteMetrics.ble.driver

import com.athletedata.openAthleteMetrics.data.model.MetricType
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests [ManifestValidator]'s CUSTOM-strategy export-name check (`ManifestValidator.kt:51-78`),
 * which catches a typo'd `wasmExport` at manifest-load time rather than only discovering it
 * during a live sync.
 *
 * The module used here is hand-encoded (no WAT toolchain is available in this
 * repo/environment, same convention as WasmCustomPredicateEngineTest) and exports a single
 * function, `predicate`, alongside its memory:
 * ```wat
 * (module
 *   (memory (export "memory") 1)
 *   (func (export "predicate") (param i32 i32 i32 i32) (result i32)
 *     local.get 0
 *     i32.load8_u
 *     i32.const 42
 *     i32.eq)
 * )
 * ```
 * `ManifestValidator` only inspects export *names* — it never executes the module — so this
 * minimal single-export module is sufficient.
 */
class ManifestValidatorTest {

    private val validWasmBytes: ByteArray = (
        "00 61 73 6d 01 00 00 00" +
            // type section: [(i32 i32 i32 i32) -> i32]
            "01 09 01 60 04 7f 7f 7f 7f 01 7f" +
            // function section: [type0]
            "03 02 01 00" +
            // memory section: 1 memory, min 1 page
            "05 03 01 00 01" +
            // export section: memory -> mem0, predicate -> func0
            "07 16 02" +
            "06 6d 65 6d 6f 72 79 02 00" +
            "09 70 72 65 64 69 63 61 74 65 00 00" +
            // code section: predicate body
            "0a 0c 01" +
            "0a 00 20 00 2d 00 00 41 2a 46 0b"
        ).replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val malformedWasmBytes: ByteArray = byteArrayOf(0x00, 0x00, 0x00, 0x00)

    private fun manifest(
        wasmBytes: ByteArray,
        customWasmExport: String?,
    ): WasmDriverManifest = WasmDriverManifest(
        id = "manifest-validator-test",
        displayName = "Test",
        version = "1.0.0",
        author = "test",
        supportedMetrics = listOf(MetricType.HR),
        ble = BleConfig(
            matchConfidence = MatchConfidence.CERTAIN,
            services = listOf("0000180d-0000-1000-8000-00805f9b34fb"),
            characteristics = mapOf(
                "write" to "00002a39-0000-1000-8000-00805f9b34fb",
                "notify" to "00002a37-0000-1000-8000-00805f9b34fb",
            ),
        ),
        syncCommands = if (customWasmExport == null) {
            emptyList()
        } else {
            listOf(
                SyncCommand.Write(
                    characteristic = "write",
                    bytes = "0x01",
                    awaitEndOfStream = AwaitEndOfStream(
                        characteristic = "notify",
                        strategy = "CUSTOM",
                        params = buildJsonObject { put("wasmExport", JsonPrimitive(customWasmExport)) },
                    ),
                ),
            )
        },
        parsing = ParsingConfig.WasmParsing(
            wasmBytes = wasmBytes,
            exports = WasmExports(parseMetrics = "parseMetrics"),
        ),
        specVersion = "2",
    )

    @Test
    fun `validate reports an error naming the missing CUSTOM wasmExport`() {
        val manifest = manifest(validWasmBytes, customWasmExport = "doesNotExist")

        val errors = ManifestValidator().validate(manifest)

        assertTrue(
            "expected an error naming the missing export, got: $errors",
            errors.any { it.contains("doesNotExist") && it.contains("not exported") },
        )
    }

    @Test
    fun `validate reports no CUSTOM-export error when the wasmExport is actually present`() {
        val manifest = manifest(validWasmBytes, customWasmExport = "predicate")

        val errors = ManifestValidator().validate(manifest)

        assertTrue(
            "did not expect a CUSTOM wasmExport error, got: $errors",
            errors.none { it.contains("wasmExport") },
        )
    }

    @Test
    fun `validate does not double-report when wasmBytes is malformed and a CUSTOM strategy is present`() {
        val manifest = manifest(malformedWasmBytes, customWasmExport = "predicate")

        val errors = ManifestValidator().validate(manifest)

        assertTrue(
            "expected exactly one error (the magic-header error), got: $errors",
            errors.size == 1,
        )
        assertTrue(
            "expected the magic header error, got: $errors",
            errors[0].contains("magic header"),
        )
    }

    // CHANGED: sessionGapThresholdMs positivity check
    @Test
    fun `validate reports an error when sessionGapThresholdMs is zero or negative`() {
        val manifest = manifest(validWasmBytes, customWasmExport = null).copy(sessionGapThresholdMs = 0L)

        val errors = ManifestValidator().validate(manifest)

        assertTrue(
            "expected an error naming sessionGapThresholdMs, got: $errors",
            errors.any { it.contains("sessionGapThresholdMs") },
        )
    }

    @Test
    fun `validate reports no error when sessionGapThresholdMs is a positive value`() {
        val manifest = manifest(validWasmBytes, customWasmExport = null).copy(sessionGapThresholdMs = 1_800_000L)

        val errors = ManifestValidator().validate(manifest)

        assertTrue(
            "did not expect a sessionGapThresholdMs error, got: $errors",
            errors.none { it.contains("sessionGapThresholdMs") },
        )
    }

    // CHANGED: intra-driver signature collision check
    @Test
    fun `validate reports a conflicting-strategy error when the same characteristic and opcode declare two different EOS strategies`() {
        val manifest = manifest(validWasmBytes, customWasmExport = null).copy(
            syncCommands = listOf(
                SyncCommand.Write(
                    characteristic = "write",
                    bytes = "0x10",
                    awaitEndOfStream = AwaitEndOfStream(
                        characteristic = "notify",
                        strategy = "SENTINEL_PACKET",
                        params = buildJsonObject { put("terminatorByte", JsonPrimitive(255)) },
                    ),
                ),
                SyncCommand.Write(
                    characteristic = "write",
                    bytes = "0x10",
                    awaitEndOfStream = AwaitEndOfStream(
                        characteristic = "notify",
                        strategy = "IN_STREAM_TERMINATOR",
                        params = buildJsonObject { put("terminatorBytes", JsonPrimitive("0xFF")) },
                    ),
                ),
            ),
        )

        val errors = ManifestValidator().validate(manifest)

        assertTrue(
            "expected a conflicting-strategy error naming the characteristic and opcode, got: $errors",
            errors.any {
                it.contains("conflicting") &&
                    it.contains("00002a37-0000-1000-8000-00805f9b34fb") &&
                    it.contains("0x10")
            },
        )
    }

    // CHANGED: intra-driver signature collision check — same characteristic+opcode+strategy
    // repeated across commands is not a conflict (no distinct strategies to disagree).
    @Test
    fun `validate reports no conflicting-strategy error when repeated commands share the same characteristic, opcode and strategy`() {
        val write = SyncCommand.Write(
            characteristic = "write",
            bytes = "0x10",
            awaitEndOfStream = AwaitEndOfStream(
                characteristic = "notify",
                strategy = "SENTINEL_PACKET",
                params = buildJsonObject { put("terminatorByte", JsonPrimitive(255)) },
            ),
        )
        val manifest = manifest(validWasmBytes, customWasmExport = null).copy(syncCommands = listOf(write, write))

        val errors = ManifestValidator().validate(manifest)

        assertTrue(
            "did not expect a conflicting-strategy error, got: $errors",
            errors.none { it.contains("conflicting") },
        )
    }
}
