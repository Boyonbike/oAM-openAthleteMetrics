package com.athletedata.openAthleteMetrics.ble.wasm

import com.athletedata.openAthleteMetrics.data.model.MetricType
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasm.Parser
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import kotlinx.serialization.json.Json
import org.junit.Test
import java.util.Base64

/**
 * Regression test for the hume_band.wat multi-chunk parseSession memory-corruption bug.
 *
 * $parseSession builds JSON into a scratch buffer (TEMP_OUT) and copies it, unbounded, down
 * to OUT_OFFSET at the end of every call. Before the fix, OUT_OFFSET only had 51,200 bytes of
 * headroom before a block of static lookup tables ($scan_opcode's pattern markers, the base64
 * table, string constants); a session whose first chunk produced more output than that silently
 * overwrote those tables, breaking opcode routing (and therefore output) for every later chunk
 * in the same sync — regardless of that chunk's own opcode.
 *
 * This drives the real compiled hume_band.wasm (loaded from the test resource, rebuilt from
 * hume_band.wat by wat2wasm) directly via Chicory, replicating exactly what
 * [WasmDriverEngine.callParseSessionBytes] does (write JSON input at offset 16, call the
 * "parseSession" export, read the JSON result at offset 0x1000) against a single shared
 * [Instance] across two sequential calls — the same reuse-across-chunks pattern the production
 * engine uses for a real sync. buildFramesJson/JSONObject aren't used here because Android's
 * org.json is an unmocked stub under plain JUnit; input JSON is built with plain string
 * templates instead, matching the exact wire format `[{"characteristic":..,"opcode":..,"bytes":..}]`.
 *
 * Chunk 1 is three sleep frames whose per-minute run-length encoding alone produces ~78 KB of
 * output (comfortably past the old 51,200-byte ceiling, comfortably under TEMP_OUT's own
 * separate ~195 KB ceiling) plus a large low-output filler frame to push the total session size
 * past the app's 50,000-byte chunking threshold; chunk 2 carries one frame each for opcodes
 * 0x53 (sleep), 0x56 (HRV), 0x65 (temperature) and 0x66 (SpO2). Against the unfixed binary,
 * chunk 2 must return "[]" for every opcode; against the fixed binary, all four readings must
 * come back correctly parsed.
 */
class WasmHumeBandParseSessionTest {

    private val json = Json { ignoreUnknownKeys = true }

    // Mirrors WasmDriverEngine's private IN_OFFSET_V2 / OUT_OFFSET companion constants.
    private val IN_OFFSET = 16
    private val OUT_OFFSET = 0x1000

    // ---------------------------------------------------------------------------
    // BCD / byte-layout helpers matching the wire protocol (see
    // "Driver Builds/Hume Band 1/Hume Band 1 BLE Protocol.md")
    // ---------------------------------------------------------------------------

    private fun bcd(v: Int): Byte = (((v / 10) shl 4) or (v % 10)).toByte()

    /** 0x53 sleep record: opcode, idx LE16, BCD ts x6, count, count stage bytes, zero padding to 130. */
    private fun sleepRecord(minute: Int, count: Int, stages: ByteArray): ByteArray {
        val header = byteArrayOf(
            0x53, 0x00, 0x00,
            bcd(26), bcd(6), bcd(15), bcd(10), bcd(minute), bcd(0),
            count.toByte(),
        )
        val padding = ByteArray(130 - header.size - stages.size)
        return header + stages + padding
    }

    /** 0x56 HRV record: opcode, idx LE16, BCD ts x6, rmssd, vascular/hr/stress (unused), sys, dia. */
    private fun hrvRecord(rmssd: Int, sys: Int, dia: Int): ByteArray = byteArrayOf(
        0x56, 0x00, 0x00,
        bcd(26), bcd(6), bcd(15), bcd(12), bcd(0), bcd(0),
        rmssd.toByte(), 0, 0, 0, sys.toByte(), dia.toByte(),
    )

    /** 0x65 temperature record: opcode, idx LE16, BCD ts x6, raw LE16 (x0.1 degC). */
    private fun tempRecord(rawTenthsDegC: Int): ByteArray = byteArrayOf(
        0x65, 0x00, 0x00,
        bcd(26), bcd(6), bcd(15), bcd(13), bcd(0), bcd(0),
        (rawTenthsDegC and 0xFF).toByte(), ((rawTenthsDegC shr 8) and 0xFF).toByte(),
    )

    /** 0x66 SpO2 record: opcode, idx LE16, BCD ts x6, percentage. */
    private fun spo2Record(percent: Int): ByteArray = byteArrayOf(
        0x66, 0x00, 0x00,
        bcd(26), bcd(6), bcd(15), bcd(14), bcd(0), bcd(0),
        percent.toByte(),
    )

    private fun alternatingStages(count: Int): ByteArray =
        ByteArray(count) { i -> if (i % 2 == 0) 1 else 2 }

    /** Builds one frame's JSON object, matching WasmDriverEngine.buildFramesJson's wire format. */
    private fun frameJson(characteristic: String, opcode: String, bytes: ByteArray): String {
        val b64 = Base64.getEncoder().encodeToString(bytes)
        return "{\"characteristic\":\"$characteristic\",\"opcode\":\"$opcode\",\"bytes\":\"$b64\"}"
    }

    // ---------------------------------------------------------------------------
    // WASM plumbing
    // ---------------------------------------------------------------------------

    private fun loadWasmBytes(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("wasm/hume_band.wasm")!!.readBytes()

    /** Mirrors WasmDriverEngine.callParseSessionBytes: write input at offset 16, call the
     * export, read the JSON result at OUT_OFFSET. Reused across calls on the same [instance]. */
    private fun callParseSession(instance: Instance, chunkJson: String): String {
        val memory = instance.memory()
        val data = chunkJson.toByteArray(Charsets.UTF_8)
        memory.write(IN_OFFSET, data)
        val result = instance.export("parseSession").apply(IN_OFFSET.toLong(), data.size.toLong())
        val outLen = result[0].toInt()
        return if (outLen <= 0) "[]" else memory.readString(OUT_OFFSET, outLen)
    }

    // ---------------------------------------------------------------------------
    // The regression test
    // ---------------------------------------------------------------------------

    @Test
    fun `chunk 2 parses correctly after chunk 1 produces a large output`() {
        val instance = Instance.builder(Parser.parse(loadWasmBytes())).build()

        // Chunk 1: 3 amplifier sleep frames, each with 120 alternating per-minute stage
        // transitions — small input, but ~26 KB of run-length-encoded output each (~78 KB
        // combined) — plus one large low-output filler frame (opcode unrecognized, oversized
        // "bytes" so $parseSession's skip_oversized guard skips decoding it entirely) to push
        // the total session size just past WASM_SAFE_INPUT_BYTES (50,000).
        val amplifierFrames = (0 until 3).map { i ->
            frameJson("notify", "0x53", sleepRecord(minute = 10 + i, count = 120, stages = alternatingStages(120)))
        }
        val fillerFrame = frameJson("notify", "0x99", ByteArray(37_692))
        val chunk1Json = "[" + (amplifierFrames + fillerFrame).joinToString(",") + "]"

        // Chunk 2: one frame per target opcode.
        val targetSleep = frameJson("notify", "0x53", sleepRecord(minute = 11, count = 2, stages = byteArrayOf(3, 3))) // DEEP x2
        val targetHrv = frameJson("notify", "0x56", hrvRecord(rmssd = 77, sys = 118, dia = 76))
        val targetTemp = frameJson("notify", "0x65", tempRecord(rawTenthsDegC = 366)) // 36.6 degC
        val targetSpo2 = frameJson("notify", "0x66", spo2Record(percent = 97))
        val chunk2Json = "[" + listOf(targetSleep, targetHrv, targetTemp, targetSpo2).joinToString(",") + "]"

        // Chunk 1's own output must be well-formed and unaffected by the fix: 3 frames x 120
        // alternating stage transitions = 360 SLEEP_STAGE readings from the amplifier frames.
        val chunk1Result = callParseSession(instance, chunk1Json)
        val chunk1Readings = json.decodeFromString<List<MetricWasmDto>>(chunk1Result)
        val amplifierCount = chunk1Readings.count {
            it.metricType == MetricType.SLEEP_STAGE &&
                (it.metaJson?.contains("AWAKE") == true || it.metaJson?.contains("LIGHT") == true)
        }
        assertEquals(360, amplifierCount)

        // Chunk 2, called on the SAME instance — absent pre-fix (returns "[]" for every opcode
        // once $scan_opcode's pattern table is corrupted), present and correctly parsed post-fix.
        val chunk2Result = callParseSession(instance, chunk2Json)
        assertTrue("expected non-empty output for chunk 2, got: $chunk2Result", chunk2Result != "[]")
        val chunk2Readings = json.decodeFromString<List<MetricWasmDto>>(chunk2Result)

        val deepSleep = chunk2Readings.firstOrNull {
            it.metricType == MetricType.SLEEP_STAGE && it.metaJson?.contains("DEEP") == true
        }
        assertTrue("expected a DEEP SLEEP_STAGE reading from chunk 2", deepSleep != null)

        val hrv = chunk2Readings.firstOrNull { it.metricType == MetricType.HRV }
        assertTrue("expected an HRV reading from chunk 2", hrv != null)
        assertEquals(77.0, hrv!!.value)

        val bp = chunk2Readings.firstOrNull { it.metricType == MetricType.BLOOD_PRESSURE }
        assertTrue("expected a BLOOD_PRESSURE reading from chunk 2", bp != null)
        assertEquals(118.0, bp!!.value)
        assertTrue(
            "expected diastolic=76 in metaJson, got ${bp.metaJson}",
            bp.metaJson?.contains("76") == true,
        )

        val temp = chunk2Readings.firstOrNull { it.metricType == MetricType.SKIN_TEMP }
        assertTrue("expected a SKIN_TEMP reading from chunk 2", temp != null)
        assertEquals(36.6, temp!!.value, 0.01)

        val spo2 = chunk2Readings.firstOrNull { it.metricType == MetricType.SPO2 }
        assertTrue("expected an SPO2 reading from chunk 2", spo2 != null)
        assertEquals(97.0, spo2!!.value)
    }
}
