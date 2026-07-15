package com.athletedata.openAthleteMetrics.architecture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// EXTENSION POINT: when a new driver introduces its own reserved opcode range or vendor
// name, add a VendorTokenRegistry entry here (and to REGISTRIES below). This mirrors the
// existing hand-curated forbidden-literal lists in EndOfStreamStrategyTest
// ("EndOfStreamStrategy source contains no Hume Band opcode or vendor literal") and
// MetricRouterTest ("MetricRouter source contains no driver-identity branching or
// device-specific literal") — opcodes aren't derivable from WasmDriverManifest JSON (no
// structured `opcodes` field; they only exist inside the compiled parsing.wasmBase64 blob),
// so this registry is deliberately hand-maintained.
private val HUME_BAND_REGISTRY = VendorTokenRegistry(
    vendorName = "Hume Band",
    forbiddenOpcodes = listOf("0x52", "0x53", "0x55", "0x56", "0x65", "0x66"),
    forbiddenNameSubstrings = listOf("Hume", "hume_band"),
    guardConstantName = "HUME_BAND_DRIVER_ID_PREFIX",
    guardConstantValue = "hume_band",
)

private val REGISTRIES = listOf(HUME_BAND_REGISTRY)

/**
 * Build-time guard against the recurring bug class where a vendor-specific opcode or driver
 * name is hardcoded straight into generic engine/sync-layer code and consulted
 * unconditionally, regardless of which driver is active. Generalizes the per-file pattern
 * already established by EndOfStreamStrategyTest and MetricRouterTest into one reusable
 * scanner covering all of `ble/` and `worker/`.
 */
class VendorLiteralGuardTest {

    @Test
    fun `scoped opcode guarded by named driverId-prefix constant is exempt`() {
        val source = """
            package com.athletedata.openAthleteMetrics.ble.sync

            private const val HUME_BAND_DRIVER_ID_PREFIX = "hume_band"

            private fun handle(driverId: String, opcode: Int) {
                if (driverId.startsWith(HUME_BAND_DRIVER_ID_PREFIX)) {
                    when (opcode) {
                        0x52 -> doSomething()
                    }
                }
            }
        """.trimIndent()

        val violations = VendorLiteralScanner.scan("FakeEngine.kt", source, HUME_BAND_REGISTRY)

        assertTrue(
            "expected no violations for an opcode properly scoped behind a named driverId-prefix " +
                "constant, got:\n" + violations.joinToString("\n") { it.message(HUME_BAND_REGISTRY) },
            violations.isEmpty(),
        )
    }

    @Test
    fun `opcode guard weakened to always-true is caught`() {
        val source = """
            package com.athletedata.openAthleteMetrics.ble.sync

            private const val HUME_BAND_DRIVER_ID_PREFIX = "hume_band"

            private fun handle(driverId: String, opcode: Int) {
                if (true) {
                    when (opcode) {
                        0x52 -> doSomething()
                    }
                }
            }
        """.trimIndent()

        val violations = VendorLiteralScanner.scan("FakeEngine.kt", source, HUME_BAND_REGISTRY)

        assertTrue(
            "expected the weakened (always-true) guard to no longer exempt opcode 0x52, but got no matching violation",
            violations.any { it.token == "0x52" && it.reason == "OPCODE_OUTSIDE_GUARD_SCOPE" },
        )
    }

    @Test
    fun `opcode guarded only by an inline re-typed string literal is not exempt`() {
        val source = """
            package com.athletedata.openAthleteMetrics.ble.sync

            private fun handle(driverId: String, opcode: Int) {
                if (driverId.startsWith("hume_band")) {
                    when (opcode) {
                        0x52 -> doSomething()
                    }
                }
            }
        """.trimIndent()

        val violations = VendorLiteralScanner.scan("FakeEngine.kt", source, HUME_BAND_REGISTRY)

        assertTrue(
            "an inline re-typed string literal must not count as a valid driverId guard for opcode 0x52",
            violations.any { it.token == "0x52" && it.reason == "OPCODE_OUTSIDE_GUARD_SCOPE" },
        )
        assertTrue(
            "a re-typed vendor name literal (\"hume_band\") is only exempt as the declared value of the " +
                "named guard constant, never as an inline literal elsewhere",
            violations.any { it.token == "hume_band" && it.reason == "NAME_SUBSTRING_OUTSIDE_DECLARATION" },
        )
    }

    @Test
    fun `ble and worker production source contains no unscoped vendor opcode or name literal`() {
        val roots = listOf(
            "src/main/java/com/athletedata/openAthleteMetrics/ble",
            "src/main/java/com/athletedata/openAthleteMetrics/worker",
        )
        val files = roots.flatMap { root ->
            val dir = File(root)
            assertTrue("expected to find production source directory at ${dir.absolutePath}", dir.exists())
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
        assertTrue("expected to find .kt files under $roots", files.isNotEmpty())
        assertFalse(
            "guard must only ever scan production source, never test source",
            files.any { it.path.contains("${File.separator}test${File.separator}") },
        )

        val messages = REGISTRIES.flatMap { registry ->
            files.flatMap { file ->
                VendorLiteralScanner.scan(file.path, file.readText(), registry).map { it.message(registry) }
            }
        }

        assertTrue(
            "found vendor-literal leakage in production source:\n" + messages.joinToString("\n"),
            messages.isEmpty(),
        )
    }

    // ---------------------------------------------------------------------------
    // MANUAL DEMONSTRATION — intentionally not automated, not part of `check`. Performed
    // once during development of this guard to prove the failure path end-to-end:
    //   1. Temporarily paste a bare, unscoped opcode (e.g. 0x52) into MetricRouter.kt, with
    //      no driverId guard anywhere in the file.
    //   2. Run the real-source-scan test above and confirm it fails, naming the file, line,
    //      and token, with the exemption criteria spelled out in the failure message.
    //   3. Revert the edit (`git checkout -- .../MetricRouter.kt`) and rerun to confirm green.
    // ---------------------------------------------------------------------------
}
