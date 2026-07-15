package com.athletedata.openAthleteMetrics.ble.driver

import com.athletedata.openAthleteMetrics.ble.wasm.WasmDriverEngine
import com.athletedata.openAthleteMetrics.data.model.MetricType
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests [DriverRegistry.register]'s cross-driver signature collision check — compares an
 * incoming manifest's [DriverCommandSignature] set (see ManifestValidator.kt) against every
 * currently registered driver's signature set. Collisions are surfaced via the returned
 * warning list (and Timber-logged) rather than blocking registration.
 */
class DriverRegistryTest {

    private val driverStorage: DriverStorage = mockk(relaxed = true)
    private val wasmEngine: WasmDriverEngine = mockk(relaxed = true)
    private val registry = DriverRegistry(driverStorage, wasmEngine)

    private fun manifestWith(
        id: String,
        characteristicUuid: String,
        opcodeHex: String,
        strategy: String,
        strategyParams: kotlinx.serialization.json.JsonObject,
    ): WasmDriverManifest = WasmDriverManifest(
        id = id,
        displayName = id,
        version = "1.0.0",
        author = "test",
        supportedMetrics = listOf(MetricType.HR),
        ble = BleConfig(
            matchConfidence = MatchConfidence.CERTAIN,
            services = listOf("0000180d-0000-1000-8000-00805f9b34fb"),
            characteristics = mapOf(
                "write" to "00002a39-0000-1000-8000-00805f9b34fb",
                "notify" to characteristicUuid,
            ),
        ),
        syncCommands = listOf(
            SyncCommand.Write(
                characteristic = "write",
                bytes = opcodeHex,
                awaitEndOfStream = AwaitEndOfStream(
                    characteristic = "notify",
                    strategy = strategy,
                    params = strategyParams,
                ),
            ),
        ),
        parsing = ParsingConfig.WasmParsing(
            wasmBytes = ByteArray(0),
            exports = WasmExports(parseMetrics = "parseMetrics"),
        ),
    )

    @Test
    fun `register surfaces a warning when two drivers declare colliding signatures`() = runTest {
        val driverA = manifestWith(
            id = "driver-a",
            characteristicUuid = "0000fff7-0000-1000-8000-00805f9b34fb",
            opcodeHex = "0x10",
            strategy = "SENTINEL_PACKET",
            strategyParams = buildJsonObject { put("terminatorByte", JsonPrimitive(255)) },
        )
        val driverB = manifestWith(
            id = "driver-b",
            characteristicUuid = "0000fff7-0000-1000-8000-00805f9b34fb",
            opcodeHex = "0x10",
            strategy = "IN_STREAM_TERMINATOR",
            strategyParams = buildJsonObject { put("terminatorBytes", JsonPrimitive("0xFF")) },
        )

        val firstWarnings = registry.register(driverA)
        assertTrue("first registration should not warn, got: $firstWarnings", firstWarnings.isEmpty())

        val secondWarnings = registry.register(driverB)
        assertTrue(
            "expected a collision warning naming both drivers, got: $secondWarnings",
            secondWarnings.any { it.contains("driver-a") && it.contains("driver-b") },
        )
    }

    @Test
    fun `register does not warn for the current Hume Band and Polar H10 pair`() = runTest {
        // Mirrors the real, currently-shipped manifests: Hume Band's syncCommands are built
        // dynamically at runtime by its WASM buildSyncCommands export (empty here, matching
        // Driver Builds/Hume Band 1/HumeBandDriver.json's static declaration), and Polar H10's
        // static WRITE pmdControl command declares no awaitEndOfStream at all (matching
        // app/src/main/assets/drivers/example_wasm.json) — so today neither driver
        // contributes any signature, and this pins that "no false positive" behavior.
        val humeBand = WasmDriverManifest(
            id = "hume_band_j2208_v1",
            displayName = "Hume Band J2208",
            version = "1.0.0",
            author = "test",
            supportedMetrics = listOf(MetricType.HR, MetricType.STEPS),
            ble = BleConfig(
                matchByName = "Hume Band 434B",
                matchConfidence = MatchConfidence.CERTAIN,
                services = listOf("0000fff0-0000-1000-8000-00805f9b34fb"),
                characteristics = mapOf(
                    "notify" to "0000fff7-0000-1000-8000-00805f9b34fb",
                    "write" to "0000fff6-0000-1000-8000-00805f9b34fb",
                ),
            ),
            syncCommands = emptyList(),
            parsing = ParsingConfig.WasmParsing(
                wasmBytes = ByteArray(0),
                exports = WasmExports(buildSyncCommands = "buildSyncCommands"),
            ),
        )
        val polarH10 = WasmDriverManifest(
            id = "polar_h10_wasm_v1",
            displayName = "Polar H10",
            version = "1.0.0",
            author = "test",
            supportedMetrics = listOf(MetricType.HR),
            ble = BleConfig(
                matchByName = "Polar H10",
                matchByServiceUuid = "0000180d-0000-1000-8000-00805f9b34fb",
                matchConfidence = MatchConfidence.CERTAIN,
                services = listOf(
                    "0000180d-0000-1000-8000-00805f9b34fb",
                    "fb005c80-02e7-f387-1cad-8acd2d8df0c8",
                ),
                characteristics = mapOf(
                    "notify" to "00002a37-0000-1000-8000-00805f9b34fb",
                    "pmdData" to "fb005c82-02e7-f387-1cad-8acd2d8df0c8",
                    "pmdControl" to "fb005c81-02e7-f387-1cad-8acd2d8df0c8",
                ),
            ),
            syncCommands = listOf(
                SyncCommand.EnableNotify(characteristic = "notify"),
                SyncCommand.EnableNotify(characteristic = "pmdData"),
                SyncCommand.Write(
                    characteristic = "pmdControl",
                    bytes = "0x02 0x00 0x00 0x01 0x82 0x00 0x01 0x01 0x0E 0x00",
                ),
            ),
            parsing = ParsingConfig.WasmParsing(
                wasmBytes = ByteArray(0),
                exports = WasmExports(parseMetrics = "parseMetrics"),
            ),
        )

        val humeWarnings = registry.register(humeBand)
        val polarWarnings = registry.register(polarH10)

        assertTrue("Hume Band registration should not warn, got: $humeWarnings", humeWarnings.isEmpty())
        assertTrue("Polar H10 registration should not warn, got: $polarWarnings", polarWarnings.isEmpty())
    }

    @Test
    fun `register of a single driver with no pre-existing drivers is unaffected`() = runTest {
        val manifest = manifestWith(
            id = "solo-driver",
            characteristicUuid = "0000fff7-0000-1000-8000-00805f9b34fb",
            opcodeHex = "0x10",
            strategy = "SENTINEL_PACKET",
            strategyParams = buildJsonObject { put("terminatorByte", JsonPrimitive(255)) },
        )

        val warnings = registry.register(manifest)

        assertTrue("did not expect any warnings, got: $warnings", warnings.isEmpty())
        assertEquals(listOf(manifest), registry.allDrivers())

        // Re-registering the same driver (same id) must not "collide with itself".
        val rewarnings = registry.register(manifest)
        assertTrue("re-registration should not warn, got: $rewarnings", rewarnings.isEmpty())
        assertEquals(listOf(manifest), registry.allDrivers())
    }
}
