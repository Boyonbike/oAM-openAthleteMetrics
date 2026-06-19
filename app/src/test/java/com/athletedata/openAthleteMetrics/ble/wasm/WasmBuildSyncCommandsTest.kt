package com.athletedata.openAthleteMetrics.ble.wasm

import com.athletedata.openAthleteMetrics.ble.driver.BleConfig
import com.athletedata.openAthleteMetrics.ble.driver.MatchConfidence
import com.athletedata.openAthleteMetrics.ble.driver.ParsingConfig
import com.athletedata.openAthleteMetrics.ble.driver.SyncCommand
import com.athletedata.openAthleteMetrics.ble.driver.WasmDriverManifest
import com.athletedata.openAthleteMetrics.ble.driver.WasmExports
import com.athletedata.openAthleteMetrics.data.model.MetricType
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

/**
 * Tests for the buildSyncCommands infrastructure.
 *
 * The test WASM module is hand-encoded from the following WAT:
 * ```wat
 * (module
 *   (memory (export "memory") 1)
 *   (data (i32.const 1024) "[{\"characteristic\":\"write\",\"bytes\":\"0x01\"}]")
 *   (func (export "buildSyncCommands") (result i32) i32.const 43)
 *   (func (export "parseMetrics") (param i32 i32) (result i32) i32.const 0)
 * )
 * ```
 * The function always returns the fixed 43-byte JSON at memory offset 1024.
 * It does not read from or verify the metadata block — that is tested separately
 * via the helper-method round-trip in [testMetadataByteHelpers].
 */
class WasmBuildSyncCommandsTest {

    // ---------------------------------------------------------------------------
    // Minimal WASM binary fixture (hand-encoded, see class KDoc for WAT source)
    // ---------------------------------------------------------------------------

    private val testWasmBytes: ByteArray = (
        // magic + version
        "00 61 73 6d  01 00 00 00" +
        // type section: [() → i32, (i32 i32) → i32]
        "01 0b 02" +
        "60 00 01 7f" +           // type 0: () → i32  (buildSyncCommands)
        "60 02 7f 7f 01 7f" +     // type 1: (i32 i32) → i32  (parseMetrics)
        // function section: [type0, type1]
        "03 03 02 00 01" +
        // memory section: 1 memory, min 1 page
        "05 03 01 00 01" +
        // export section: 3 exports, content size = 45 = 0x2d
        "07 2d 03" +
        // "memory" (6 bytes) → memory 0
        "06 6d 65 6d 6f 72 79 02 00" +
        // "buildSyncCommands" (17 bytes) → function 0
        "11 62 75 69 6c 64 53 79 6e 63 43 6f 6d 6d 61 6e 64 73 00 00" +
        // "parseMetrics" (12 bytes) → function 1
        "0c 70 61 72 73 65 4d 65 74 72 69 63 73 00 01" +
        // code section: 2 bodies, content size = 11 = 0x0b
        "0a 0b 02" +
        "04 00 41 2b 0b" +        // buildSyncCommands: i32.const 43, end
        "04 00 41 00 0b" +        // parseMetrics: i32.const 0, end
        // data section: active at offset 1024, 43 data bytes, content size = 50 = 0x32
        "0b 32 01" +
        "00" +                    // mode = active, memory 0
        "41 80 08 0b" +           // i32.const 1024, end
        "2b" +                    // data length = 43
        // [{"characteristic":"write","bytes":"0x01"}]
        "5b 7b 22 63 68 61 72 61 63 74 65 72 69 73 74 69 63 22 3a 22" +
        "77 72 69 74 65 22 2c 22 62 79 74 65 73 22 3a 22 30 78 30 31" +
        "22 7d 5d"
    ).replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // ---------------------------------------------------------------------------
    // Test manifest builders
    // ---------------------------------------------------------------------------

    private val staticCommand = SyncCommand.Write(
        characteristic = "notify",
        bytes = "0x01 0x00",
    )

    private fun manifestWith(
        buildSyncCommandsExport: String?,
        wasmBytes: ByteArray = testWasmBytes,
        staticCommands: List<SyncCommand> = listOf(staticCommand),
    ) = WasmDriverManifest(
        id = "test-driver",
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
        syncCommands = staticCommands,
        parsing = ParsingConfig.WasmParsing(
            wasmBytes = wasmBytes,
            exports = WasmExports(
                parseMetrics = "parseMetrics",
                buildSyncCommands = buildSyncCommandsExport,
            ),
        ),
        specVersion = "2",
    )

    // ---------------------------------------------------------------------------
    // Test 1: dynamic commands prepended before static commands
    // ---------------------------------------------------------------------------

    @Test
    fun `buildEffectiveSyncCommands prepends dynamic commands before static`() = runBlocking {
        val engine = WasmDriverEngine()
        val manifest = manifestWith(buildSyncCommandsExport = "buildSyncCommands")

        val result = engine.buildEffectiveSyncCommands(manifest)

        assertEquals(2, result.size)

        // Dynamic command (from WASM) is first
        val dynamic = result[0] as SyncCommand.Write
        assertEquals("write", dynamic.characteristic)
        assertEquals("0x01", dynamic.bytes)

        // Static command from manifest is second
        assertEquals(staticCommand, result[1])
    }

    // ---------------------------------------------------------------------------
    // Test 2: null buildSyncCommands export skips the dynamic path entirely
    // ---------------------------------------------------------------------------

    @Test
    fun `buildEffectiveSyncCommands returns static commands when export is null`() = runBlocking {
        val engine = WasmDriverEngine()
        val manifest = manifestWith(buildSyncCommandsExport = null)

        val result = engine.buildEffectiveSyncCommands(manifest)

        assertEquals(listOf(staticCommand), result)
    }

    // ---------------------------------------------------------------------------
    // Test 3: metadata block byte-helper round-trips (longToLeBytes / shortToLeBytes)
    // ---------------------------------------------------------------------------

    @Test
    fun `metadata byte helpers round-trip correctly`() {
        val engine = WasmDriverEngine()

        // i64 round-trip — verify freshly-captured time survives LE encoding
        val nowMs = Instant.now().toEpochMilli()
        val leBytes = engine.longToLeBytes(nowMs)
        val decoded = (0..7).fold(0L) { acc, i -> acc or (leBytes[i].toLong() and 0xffL shl (i * 8)) }
        assertTrue(
            "decoded ms ($decoded) should be within 1 second of nowMs ($nowMs)",
            kotlin.math.abs(decoded - nowMs) < 1_000L,
        )

        // i16 round-trip — verify the system timezone offset round-trips through LE encoding
        val offsetMinutes = (TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000).toShort()
        val shortBytes = engine.shortToLeBytes(offsetMinutes)
        val decodedShort = ((shortBytes[1].toInt() and 0xff shl 8) or (shortBytes[0].toInt() and 0xff)).toShort()
        assertEquals(offsetMinutes, decodedShort)
    }
}
