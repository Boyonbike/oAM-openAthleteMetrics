package com.athletedata.openAthleteMetrics.ble.wasm

import com.athletedata.openAthleteMetrics.ble.driver.AwaitEndOfStream
import com.athletedata.openAthleteMetrics.ble.driver.SyncCommand
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasm.Parser
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNotNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

// regression test for the hume_band.wat awaitEndOfStream migration. First migrated
// FixedOffsetByte -> SentinelPacket (growing the shared [DDF5] template by +45 bytes,
// requiring six downstream data-segment addresses to be recomputed). Then migrated
// again, SentinelPacket -> IN_STREAM_TERMINATOR matchOpcode=true (a real device
// capture showed Hume Band's terminator is the tail of its last DATA notification,
// not a standalone 2-byte sentinel packet). The new shared template lives at a fresh
// address (0x40D00, 157 bytes) rather than resizing the old block in place, to avoid
// recomputing the same six downstream addresses again.
//
// CHANGED AGAIN: matchOpcode=true required the tail to equal [echoedOpcode, 0xFF] --
// this only ever resolved for SpO2/HRV/Temp on real hardware; HR/Steps/Sleep stalled
// to the 30s timeout. Decompiling Hume's own Android client (ResolveUtil.java) showed
// the real device-side rule is just "last raw byte == 0xFF", opcode-independent,
// identical across all six parse functions. matchOpcode=false + terminatorBytes=
// "0xFF" (1 byte) produces this exactly via InStreamTerminator's existing tail-match
// logic -- the 0x40D00 template's content changed but its byte length coincidentally
// stayed at 157 (matchOpcode:false is +1 char vs :true, terminatorBytes is -1 char
// vs terminatorSuffix), so no downstream address/length recompute was needed this
// time. This test drives the real compiled hume_band.wasm and fails loudly if any of
// that byte-offset arithmetic was missed or miscalculated, rather than silently
// producing truncated/garbled JSON or an out-of-bounds memory read.
class WasmHumeBandBuildSyncCommandsTest {

    private val json = Json { ignoreUnknownKeys = true }

    // Mirrors WasmDriverEngine's private IN_OFFSET_V2 / CMD_OUT_OFFSET companion constants.
    private val IN_OFFSET_V2 = 16
    private val CMD_OUT_OFFSET = 0x400

    private fun loadWasmBytes(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("wasm/hume_band.wasm")!!.readBytes()

    private fun longToLeBytes(value: Long): ByteArray =
        ByteArray(8) { i -> ((value shr (i * 8)) and 0xFF).toByte() }

    private fun shortToLeBytes(value: Short): ByteArray =
        ByteArray(2) { i -> ((value.toInt() shr (i * 8)) and 0xFF).toByte() }

    /** Mirrors WasmDriverEngine.callBuildSyncCommandsWithContext: metadata header at 0,
     * SyncContext JSON at IN_OFFSET_V2, call the export, read the JSON result at CMD_OUT_OFFSET.
     * $buildSyncCommands derives its "current year" (used for age = year - birth_year) from the
     * metadata header's syncStartMs, not from the JSON body's epochMs field -- syncStartMs must
     * be set explicitly for any test that exercises age. */
    private fun callBuildSyncCommands(instance: Instance, contextJson: String, syncStartMs: Long = 0L): String {
        val memory = instance.memory()
        memory.write(0, longToLeBytes(syncStartMs))
        memory.write(8, shortToLeBytes(0))
        memory.write(10, ByteArray(6))
        val contextBytes = contextJson.toByteArray(Charsets.UTF_8)
        memory.write(IN_OFFSET_V2, contextBytes)

        val result = instance.export("buildSyncCommands").apply(IN_OFFSET_V2.toLong(), contextBytes.size.toLong())
        val outLen = result[0].toInt()
        return memory.readString(CMD_OUT_OFFSET, outLen)
    }

    private val minimalContextJson =
        """{"epochMs":0,"utcOffsetMinutes":0,"isoDateTime":"2026-01-01T00:00:00"}"""

    /** Extracts the SetPersonalInfo (0x02) command's raw payload bytes as unsigned ints. */
    private fun setPersonalInfoBytes(resultJson: String): List<Int> {
        val commands = json.decodeFromString<List<SyncCommand>>(resultJson)
        val write = commands.filterIsInstance<SyncCommand.Write>()
            .single { it.bytes.startsWith("0x02") }
        return write.bytes.split(" ").map { it.removePrefix("0x").toInt(16) }
    }

    @Test
    fun `buildSyncCommands emits IN_STREAM_TERMINATOR with opcode-independent terminatorBytes and per-opcode timeoutMs for all six fetch opcodes`() {
        val instance = Instance.builder(Parser.parse(loadWasmBytes())).build()

        val resultJson = callBuildSyncCommands(instance, minimalContextJson)
        val commands = json.decodeFromString<List<SyncCommand>>(resultJson)

        // Real [EOS-RESOLVED] device captures show 0x55/0x52/0x53/0x65 never resolve via a
        // genuine terminator match (device never sends a terminal 0xFF for those streams) --
        // 8000ms is ample margin over the observed non-match behavior. 0x66/0x56 DO resolve
        // via a genuine match (observed up to ~3.3s) -- 10000ms is ample margin there without
        // waiting anywhere near the old 60000ms.
        val expectedTimeoutMsByOpcode = mapOf(
            "0x55" to 8000L,
            "0x52" to 8000L,
            "0x53" to 8000L,
            "0x66" to 10000L,
            "0x56" to 10000L,
            "0x65" to 8000L,
        )
        val fetchWrites = commands.filterIsInstance<SyncCommand.Write>().filter { write ->
            expectedTimeoutMsByOpcode.keys.any { opcode -> write.bytes.startsWith(opcode) }
        }
        assertEquals(6, fetchWrites.size)

        // All six share terminatorBytes="0xFF", matching on the last raw notification
        // byte alone with no opcode dependency (see Hume decompile note above).
        for (write in fetchWrites) {
            val opcode = expectedTimeoutMsByOpcode.keys.single { write.bytes.startsWith(it) }
            val aes: AwaitEndOfStream? = write.awaitEndOfStream
            assertNotNull("write for bytes=${write.bytes} must have awaitEndOfStream", aes)
            assertEquals("IN_STREAM_TERMINATOR", aes!!.strategy)
            assertEquals(
                "opcode=$opcode must declare its per-opcode timeoutMs",
                expectedTimeoutMsByOpcode.getValue(opcode),
                aes.timeoutMs,
            )

            val params = aes.params
            assertNotNull("awaitEndOfStream.params must be present", params)
            assertEquals(false, params!!["matchOpcode"]?.jsonPrimitive?.booleanOrNull)
            assertEquals("0xFF", params["terminatorBytes"]?.jsonPrimitive?.contentOrNull)
        }
    }

    // CHANGED: regression test for the shortPacketRecordSize manifest split. Steps/SpO2/HR
    // each got their own awaitEndOfStream template (0x40E00/0x40EC0 in hume_band.wat) with
    // shortPacketRecordSize baked into params; Sleep/HRV/Temp still share the original
    // 0x40D00 template, which never declares that key. Drives the real compiled WASM so it
    // catches any wrong address/length in the .wat data-segment split, not just a Kotlin-side
    // assumption about what the manifest should say.
    @Test
    fun `buildSyncCommands declares shortPacketRecordSize only for Steps SpO2 HR, with the correct per-opcode value`() {
        val instance = Instance.builder(Parser.parse(loadWasmBytes())).build()

        val resultJson = callBuildSyncCommands(instance, minimalContextJson)
        val commands = json.decodeFromString<List<SyncCommand>>(resultJson)
        val fetchWrites = commands.filterIsInstance<SyncCommand.Write>()

        val expectedShortPacketRecordSize = mapOf(
            "0x52" to 15, // Steps
            "0x66" to 10, // SpO2
            "0x55" to 10, // HR
            "0x53" to null, // Sleep
            "0x56" to null, // HRV
            "0x65" to null, // Temp
        )

        for ((opcode, expected) in expectedShortPacketRecordSize) {
            val write = fetchWrites.single { it.bytes.startsWith(opcode) }
            val actual = write.awaitEndOfStream?.params?.get("shortPacketRecordSize")?.jsonPrimitive?.intOrNull
            assertEquals(
                "opcode $opcode should have shortPacketRecordSize=$expected in its manifest-declared params",
                expected,
                actual,
            )
        }
    }

    // regression test for two memory-audit findings, both in the same 0x40200
    // scan-patterns block. (1) Four pattern addresses (dateOfBirth/heightCm/weightKg/
    // strideLengthCm) were each off by -1 byte, so the malformed needles never matched
    // real JSON and SetPersonalInfo always encoded hardcoded defaults (age 30, height
    // 170, weight 70, stride 70) regardless of what the app sent. (2) The biologicalSex
    // needle was hardcoded lowercase "male", but SyncContextSerializer always emits
    // uppercase "MALE"/"FEMALE"/"OTHER" (ProfileTab.kt), so sex never matched either.
    // This drives a real, non-default profile through buildSyncCommands and asserts the
    // encoded bytes reflect the supplied values.
    @Test
    fun `buildSyncCommands encodes real profile values in SetPersonalInfo, not defaults`() {
        val instance = Instance.builder(Parser.parse(loadWasmBytes())).build()

        // syncStartMs (metadata header, not the JSON body) drives $y in age = y - birth_year;
        // 1767225600000L = 2026-01-01T00:00:00Z, so dateOfBirth 1990 -> age 36.
        val syncStartMs = 1767225600000L
        val contextJson = """{"epochMs":$syncStartMs,"utcOffsetMinutes":0,"isoDateTime":"2026-01-01T00:00:00","biologicalSex":"MALE","dateOfBirth":"1990-05-14","heightCm":183,"weightKg":81,"strideLengthCm":92}"""

        val resultJson = callBuildSyncCommands(instance, contextJson, syncStartMs)
        val payload = setPersonalInfoBytes(resultJson)

        // payload layout: [0]=opcode 0x02, [1]=sex, [2]=age, [3]=height, [4]=weight, [5]=stride
        assertEquals("sex should match on real uppercase \"MALE\" input, not fall through to 0", 1, payload[1])
        assertEquals("age should be derived from dateOfBirth=1990, not the default of 30", 36, payload[2])
        assertEquals("height should reflect the supplied heightCm, not the default of 170", 183, payload[3])
        assertEquals("weight should reflect the supplied weightKg, not the default of 70", 81, payload[4])
        assertEquals("stride should reflect the supplied strideLengthCm, not the default of 70", 92, payload[5])
    }
}
