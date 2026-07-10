package com.athletedata.openAthleteMetrics.ble.driver

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class EndOfStreamStrategyTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ---------------------------------------------------------------------------
    // FixedOffsetByte
    // ---------------------------------------------------------------------------

    @Test
    fun `FixedOffsetByte completes when byte at offset matches value`() {
        val strategy = EndOfStreamStrategy.FixedOffsetByte(offset = 1, value = 255)
        assertTrue(strategy.onNotification(byteArrayOf(0x01, 0xFF.toByte()), 0))
    }

    @Test
    fun `FixedOffsetByte does not complete when byte at offset differs`() {
        val strategy = EndOfStreamStrategy.FixedOffsetByte(offset = 1, value = 255)
        assertFalse(strategy.onNotification(byteArrayOf(0x01, 0x02), 0))
    }

    @Test
    fun `FixedOffsetByte does not complete when offset is out of range`() {
        val strategy = EndOfStreamStrategy.FixedOffsetByte(offset = 1, value = 255)
        assertFalse(strategy.onNotification(byteArrayOf(0x01), 0))
    }

    @Test
    fun `Hume Band JSON with no strategy tag decodes to FixedOffsetByte matching today's check`() {
        // Regression pin: exact JSON shape currently emitted by hume_band.wat / HumeBandDriver.json.
        val jsonStr = """{"characteristic":"notify","endByte":{"offset":1,"value":255},"timeoutMs":30000}"""
        val aes = json.decodeFromString<AwaitEndOfStream>(jsonStr)
        assertEquals(null, aes.strategy)
        assertEquals(null, aes.params)

        val strategy = EndOfStreamStrategyFactory.from(aes)
        assertTrue(strategy is EndOfStreamStrategy.FixedOffsetByte)
        strategy as EndOfStreamStrategy.FixedOffsetByte
        assertEquals(1, strategy.offset)
        assertEquals(255, strategy.value)
        assertTrue(strategy.onNotification(byteArrayOf(0x00, 0xFF.toByte()), 0))
        assertFalse(strategy.onNotification(byteArrayOf(0x00, 0x00), 0))
    }

    // ---------------------------------------------------------------------------
    // SentinelPacket
    // ---------------------------------------------------------------------------

    @Test
    fun `SentinelPacket matchOpcode=false completes on standalone terminator byte`() {
        val strategy = EndOfStreamStrategy.SentinelPacket(terminatorByte = 0xFF, matchOpcode = false)
        assertTrue(strategy.onNotification(byteArrayOf(0xFF.toByte()), 0))
    }

    @Test
    fun `SentinelPacket matchOpcode=false does not trigger on incidental byte inside a normal packet`() {
        val strategy = EndOfStreamStrategy.SentinelPacket(terminatorByte = 0xFF, matchOpcode = false)
        // 0xFF appears mid-packet in an otherwise-normal 3-byte data packet — must not trigger.
        assertFalse(strategy.onNotification(byteArrayOf(0x01, 0xFF.toByte(), 0x02), 0))
    }

    @Test
    fun `SentinelPacket matchOpcode=false does not trigger on wrong value`() {
        val strategy = EndOfStreamStrategy.SentinelPacket(terminatorByte = 0xFF, matchOpcode = false)
        assertFalse(strategy.onNotification(byteArrayOf(0x01), 0))
    }

    @Test
    fun `SentinelPacket matchOpcode=true completes on echoed opcode plus terminator`() {
        val strategy = EndOfStreamStrategy.SentinelPacket(terminatorByte = 0x01, matchOpcode = true)
        strategy.onStreamStart(0x55)
        assertTrue(strategy.onNotification(byteArrayOf(0x55, 0x01), 0))
    }

    @Test
    fun `SentinelPacket matchOpcode=true does not trigger when the pair is a prefix of a longer packet`() {
        val strategy = EndOfStreamStrategy.SentinelPacket(terminatorByte = 0x01, matchOpcode = true)
        strategy.onStreamStart(0x55)
        // [opcode, terminatorByte] appears as a prefix of a longer, otherwise-normal packet.
        assertFalse(strategy.onNotification(byteArrayOf(0x55, 0x01, 0x02), 0))
    }

    @Test
    fun `SentinelPacket matchOpcode=true does not trigger on opcode mismatch`() {
        val strategy = EndOfStreamStrategy.SentinelPacket(terminatorByte = 0x01, matchOpcode = true)
        strategy.onStreamStart(0x55)
        assertFalse(strategy.onNotification(byteArrayOf(0x22, 0x01), 0))
    }

    @Test
    fun `SentinelPacket matchOpcode=true matches the latest onStreamStart opcode, not a stale one`() {
        val strategy = EndOfStreamStrategy.SentinelPacket(terminatorByte = 0x01, matchOpcode = true)
        strategy.onStreamStart(0x55)
        strategy.onStreamStart(0x22) // re-armed for a different command reusing this instance
        assertFalse(strategy.onNotification(byteArrayOf(0x55, 0x01), 0))
        assertTrue(strategy.onNotification(byteArrayOf(0x22, 0x01), 0))
    }

    // Hume Band's six fetch commands (GetHR 0x55, GetSteps 0x52, GetSleep 0x53,
    // GetSpO2 0x66, GetHRV 0x56, GetTemp 0x65). Shared opcode list reused by the
    // SentinelPacket and InStreamTerminator sections below for exercising the
    // generic matching mechanisms against realistic opcode values — NOT a claim
    // about which strategy the current driver actually uses. As of the Hume
    // decompile fix, the driver uses InStreamTerminator(terminatorBytes=[0xFF])
    // (opcode-independent, see that section below), not SentinelPacket or
    // InStreamTerminator's matchOpcode=true mode.
    private val humeBandFetchOpcodes = listOf(0x55, 0x52, 0x53, 0x66, 0x56, 0x65)

    @Test
    fun `SentinelPacket resolves the standalone sentinel for every Hume Band fetch opcode`() {
        for (opcode in humeBandFetchOpcodes) {
            val strategy = EndOfStreamStrategy.SentinelPacket(terminatorByte = 255, matchOpcode = true)
            strategy.onStreamStart(opcode)
            assertTrue(
                "opcode 0x%02X should resolve on its own [opcode,0xFF] sentinel".format(opcode),
                strategy.onNotification(byteArrayOf(opcode.toByte(), 0xFF.toByte()), 0),
            )
        }
    }

    @Test
    fun `SentinelPacket does not resolve early on a realistic longer record containing 0xFF for every Hume Band fetch opcode`() {
        for (opcode in humeBandFetchOpcodes) {
            val strategy = EndOfStreamStrategy.SentinelPacket(terminatorByte = 255, matchOpcode = true)
            strategy.onStreamStart(opcode)
            // Realistic record shape: [opcode][2-byte LE sequence counter][payload], with
            // 0xFF appearing incidentally in the payload — must not be mistaken for the
            // standalone 2-byte sentinel just because it contains a matching byte value.
            val record = byteArrayOf(
                opcode.toByte(), 0x01, 0x00, 0xFF.toByte(), 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            )
            assertFalse(
                "opcode 0x%02X should not resolve early on a longer record containing 0xFF".format(opcode),
                strategy.onNotification(record, 0),
            )
        }
    }

    // ---------------------------------------------------------------------------
    // InStreamTerminator
    // ---------------------------------------------------------------------------

    @Test
    fun `InStreamTerminator completes when the packet tail matches`() {
        val strategy = EndOfStreamStrategy.InStreamTerminator(byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
        assertTrue(strategy.onNotification(byteArrayOf(0x01, 0x02, 0xFF.toByte(), 0xFF.toByte()), 0))
    }

    @Test
    fun `InStreamTerminator does not trigger when the terminator sequence is embedded mid-packet`() {
        val strategy = EndOfStreamStrategy.InStreamTerminator(byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
        // Terminator sequence appears mid-packet; the actual tail is [0x02, 0x03].
        assertFalse(strategy.onNotification(byteArrayOf(0x01, 0xFF.toByte(), 0xFF.toByte(), 0x02, 0x03), 0))
    }

    @Test
    fun `InStreamTerminator does not trigger when the packet is shorter than the terminator`() {
        val strategy = EndOfStreamStrategy.InStreamTerminator(byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
        assertFalse(strategy.onNotification(byteArrayOf(0xFF.toByte()), 0))
    }

    @Test
    fun `InStreamTerminator completes when the whole packet is exactly the terminator`() {
        val strategy = EndOfStreamStrategy.InStreamTerminator(byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
        assertTrue(strategy.onNotification(byteArrayOf(0xFF.toByte(), 0xFF.toByte()), 0))
    }

    @Test
    fun `InStreamTerminator matchOpcode=true completes when the tail is echoedOpcode plus terminatorSuffix`() {
        val strategy = EndOfStreamStrategy.InStreamTerminator(matchOpcode = true, terminatorSuffix = byteArrayOf(0xFF.toByte()))
        strategy.onStreamStart(0x66)
        // Realistic shape: real payload bytes, then the trailing [opcode, 0xFF] tail.
        val record = byteArrayOf(0x66, 0x01, 0x02, 0x03, 0x66, 0xFF.toByte())
        assertTrue(strategy.onNotification(record, 0))
    }

    @Test
    fun `InStreamTerminator matchOpcode=true does not trigger on opcode mismatch`() {
        val strategy = EndOfStreamStrategy.InStreamTerminator(matchOpcode = true, terminatorSuffix = byteArrayOf(0xFF.toByte()))
        strategy.onStreamStart(0x66)
        assertFalse(strategy.onNotification(byteArrayOf(0x22, 0xFF.toByte()), 0))
    }

    @Test
    fun `InStreamTerminator matchOpcode=true uses the latest onStreamStart opcode, not a stale one`() {
        val strategy = EndOfStreamStrategy.InStreamTerminator(matchOpcode = true, terminatorSuffix = byteArrayOf(0xFF.toByte()))
        strategy.onStreamStart(0x66)
        strategy.onStreamStart(0x55) // re-armed for a different command reusing this instance
        assertFalse(strategy.onNotification(byteArrayOf(0x66, 0xFF.toByte()), 0))
        assertTrue(strategy.onNotification(byteArrayOf(0x55, 0xFF.toByte()), 0))
    }

    @Test
    fun `InStreamTerminator matchOpcode=true resolves for every Hume Band fetch opcode`() {
        for (opcode in humeBandFetchOpcodes) {
            val strategy = EndOfStreamStrategy.InStreamTerminator(matchOpcode = true, terminatorSuffix = byteArrayOf(0xFF.toByte()))
            strategy.onStreamStart(opcode)
            // Realistic capture shape: real data-record bytes precede the trailing [opcode,0xFF]
            // tail in the SAME notification — unlike SentinelPacket, this is not a standalone packet.
            val record = byteArrayOf(opcode.toByte(), 0x01, 0x00, 0x02, 0x03, 0x04, opcode.toByte(), 0xFF.toByte())
            assertTrue(
                "opcode 0x%02X should resolve on a trailing [opcode,0xFF] tail with real data preceding it".format(opcode),
                strategy.onNotification(record, 0),
            )
        }
    }

    @Test
    fun `InStreamTerminator matchOpcode=true does not resolve early on an incidental mid-packet match`() {
        for (opcode in humeBandFetchOpcodes) {
            val strategy = EndOfStreamStrategy.InStreamTerminator(matchOpcode = true, terminatorSuffix = byteArrayOf(0xFF.toByte()))
            strategy.onStreamStart(opcode)
            // [opcode, 0xFF] appears mid-packet, followed by more real data — the actual tail
            // is [0x03, 0x04], which must not match.
            val record = byteArrayOf(opcode.toByte(), 0x01, opcode.toByte(), 0xFF.toByte(), 0x02, 0x03, 0x04)
            assertFalse(
                "opcode 0x%02X should not resolve early on a mid-packet incidental match".format(opcode),
                strategy.onNotification(record, 0),
            )
        }
    }

    // Hume decompile (ResolveUtil.java): the real device-side rule for all six fetch
    // opcodes is "last raw notification byte == 0xFF", full stop — no dependency on
    // the opcode or the byte preceding it. matchOpcode=false + terminatorBytes=[0xFF]
    // (1 byte) already produces exactly this via the existing tail-match logic above.

    @Test
    fun `InStreamTerminator terminatorBytes 0xFF resolves on any preceding byte, opcode-independent`() {
        for (opcode in humeBandFetchOpcodes) {
            val strategy = EndOfStreamStrategy.InStreamTerminator(terminatorBytes = byteArrayOf(0xFF.toByte()))
            strategy.onStreamStart(opcode)
            // The byte immediately before the terminator is NOT the opcode (just an
            // ordinary data byte) — proves the fix no longer requires [opcode, 0xFF],
            // only a trailing 0xFF, regressing neither direction.
            val record = byteArrayOf(0x01, 0x02, 0x03, 0x9E.toByte(), 0xFF.toByte())
            assertTrue(
                "opcode 0x%02X should resolve on any trailing 0xFF regardless of the preceding byte".format(opcode),
                strategy.onNotification(record, 0),
            )
        }
    }

    // ---------------------------------------------------------------------------
    // InStreamTerminator short-packet completion (Hume Band 0x52/0x66/0x55 only)
    //
    // Hume decompile: getDetailData (Steps)/getBloodoxygen (SpO2)/getOnceHeartData
    // (HR) additionally treat a notification too short to hold one full record
    // (length/recordSize == 0) as end-of-stream on its own. Sleep/Temp/HRV's
    // decompiled parse functions do NOT apply this rule.
    // ---------------------------------------------------------------------------

    private val humeBandShortPacketRecordSizes = mapOf(0x52 to 15, 0x66 to 10, 0x55 to 10)

    @Test
    fun `InStreamTerminator completes on a too-short packet for GetSteps GetSpO2 GetHR`() {
        for ((opcode, recordSize) in humeBandShortPacketRecordSizes) {
            val strategy = EndOfStreamStrategy.InStreamTerminator(terminatorBytes = byteArrayOf(0xFF.toByte()))
            strategy.onStreamStart(opcode)
            // One byte short of a full record, no trailing 0xFF — the short-packet
            // signal must fire on its own.
            val shortPacket = ByteArray(recordSize - 1)
            assertTrue(
                "opcode 0x%02X should complete on a packet shorter than its %d-byte record size".format(opcode, recordSize),
                strategy.onNotification(shortPacket, 0),
            )
        }
    }

    @Test
    fun `InStreamTerminator completes on a fully empty packet for GetSteps GetSpO2 GetHR`() {
        for (opcode in humeBandShortPacketRecordSizes.keys) {
            val strategy = EndOfStreamStrategy.InStreamTerminator(terminatorBytes = byteArrayOf(0xFF.toByte()))
            strategy.onStreamStart(opcode)
            assertTrue(
                "opcode 0x%02X should complete on an empty notification".format(opcode),
                strategy.onNotification(ByteArray(0), 0),
            )
        }
    }

    @Test
    fun `InStreamTerminator does not complete early on a full-size non-terminal packet for GetSteps GetSpO2 GetHR`() {
        for ((opcode, recordSize) in humeBandShortPacketRecordSizes) {
            val strategy = EndOfStreamStrategy.InStreamTerminator(terminatorBytes = byteArrayOf(0xFF.toByte()))
            strategy.onStreamStart(opcode)
            // Exactly one full record's worth of bytes, no trailing 0xFF — must not
            // complete via either the terminator check or the short-packet check.
            val fullRecord = ByteArray(recordSize)
            assertFalse(
                "opcode 0x%02X should not complete on a full-size record with no terminator".format(opcode),
                strategy.onNotification(fullRecord, 0),
            )
        }
    }

    @Test
    fun `InStreamTerminator does not complete on an empty packet for GetSleep GetTemp GetHRV`() {
        // Negative case: Hume's decompile does NOT apply the short-packet rule to
        // these three opcodes, unlike Steps/SpO2/HR above.
        for (opcode in listOf(0x53, 0x65, 0x56)) {
            val strategy = EndOfStreamStrategy.InStreamTerminator(terminatorBytes = byteArrayOf(0xFF.toByte()))
            strategy.onStreamStart(opcode)
            assertFalse(
                "opcode 0x%02X should NOT complete on an empty notification (short-packet rule is Steps/SpO2/HR-only)".format(opcode),
                strategy.onNotification(ByteArray(0), 0),
            )
        }
    }

    // ---------------------------------------------------------------------------
    // RecordCount
    // ---------------------------------------------------------------------------

    @Test
    fun `RecordCount Fixed completes once the expected count is reached`() {
        val strategy = EndOfStreamStrategy.RecordCount(EndOfStreamStrategy.RecordCountSource.Fixed(3))
        strategy.onStreamStart(0)
        assertFalse(strategy.onNotification(byteArrayOf(), 0))
        assertFalse(strategy.onNotification(byteArrayOf(), 0))
        assertTrue(strategy.onNotification(byteArrayOf(), 0))
    }

    @Test
    fun `RecordCount Fixed does not complete before the expected count is reached`() {
        val strategy = EndOfStreamStrategy.RecordCount(EndOfStreamStrategy.RecordCountSource.Fixed(3))
        strategy.onStreamStart(0)
        assertFalse(strategy.onNotification(byteArrayOf(), 0))
        assertFalse(strategy.onNotification(byteArrayOf(), 0))
    }

    @Test
    fun `RecordCount Fixed resets its counter on a second onStreamStart`() {
        val strategy = EndOfStreamStrategy.RecordCount(EndOfStreamStrategy.RecordCountSource.Fixed(3))
        strategy.onStreamStart(0)
        strategy.onNotification(byteArrayOf(), 0)
        strategy.onNotification(byteArrayOf(), 0)
        strategy.onStreamStart(0) // re-armed, e.g. the same command retried — counter must reset to 0
        // 1 < 3 (not "3rd notification ever seen" across the two arms) if the reset happened correctly.
        assertFalse(strategy.onNotification(byteArrayOf(), 0))
    }

    @Test
    fun `factory falls back to TimeoutOnly for RECORD_COUNT source=fromField`() {
        val params = buildJsonObject {
            put("source", JsonPrimitive("fromField"))
            put("field", JsonPrimitive("totalRecords"))
        }
        val aes = AwaitEndOfStream(characteristic = "notify", strategy = "RECORD_COUNT", params = params)
        assertTrue(EndOfStreamStrategyFactory.from(aes) is EndOfStreamStrategy.TimeoutOnly)
    }

    // ---------------------------------------------------------------------------
    // ExplicitEvent
    // ---------------------------------------------------------------------------

    @Test
    fun `ExplicitEvent completes on a matching opcode at byte 0`() {
        val strategy = EndOfStreamStrategy.ExplicitEvent(eventOpcode = 0x99)
        assertTrue(strategy.onNotification(byteArrayOf(0x99.toByte(), 0x01, 0x02), 0))
    }

    @Test
    fun `ExplicitEvent does not trigger when the opcode is at the wrong position`() {
        val strategy = EndOfStreamStrategy.ExplicitEvent(eventOpcode = 0x99)
        assertFalse(strategy.onNotification(byteArrayOf(0x01, 0x99.toByte()), 0))
    }

    @Test
    fun `ExplicitEvent does not trigger on an empty packet`() {
        val strategy = EndOfStreamStrategy.ExplicitEvent(eventOpcode = 0x99)
        assertFalse(strategy.onNotification(byteArrayOf(), 0))
    }

    // ---------------------------------------------------------------------------
    // QuietPeriod
    // ---------------------------------------------------------------------------

    @Test
    fun `QuietPeriod never completes via onNotification`() {
        val strategy = EndOfStreamStrategy.QuietPeriod(quietMs = 2000)
        assertFalse(strategy.onNotification(byteArrayOf(0x01), 500))
        assertFalse(strategy.onNotification(byteArrayOf(0x02, 0x03), 1500))
    }

    @Test
    fun `QuietPeriod isQuietNowMs is false before threshold and true after`() {
        val strategy = EndOfStreamStrategy.QuietPeriod(quietMs = 2000)
        assertFalse(strategy.isQuietNowMs(1500)) // before any notification, lastActivityMs=0
        strategy.onNotification(byteArrayOf(), 1000)
        assertFalse(strategy.isQuietNowMs(2500)) // 1500ms quiet so far, threshold 2000
        assertTrue(strategy.isQuietNowMs(3200)) // 2200ms quiet
    }

    @Test
    fun `QuietPeriod resets lastActivityMs on a second onStreamStart`() {
        val strategy = EndOfStreamStrategy.QuietPeriod(quietMs = 2000)
        strategy.onStreamStart(0)
        strategy.onNotification(byteArrayOf(), 1000)
        strategy.onStreamStart(0) // re-armed — lastActivityMs must reset to 0
        // With the reset: 2500 - 0 = 2500 >= 2000 -> true.
        // If the reset were dropped (stale lastActivityMs=1000): 2500 - 1000 = 1500 >= 2000 -> false.
        assertTrue(strategy.isQuietNowMs(2500))
    }

    // ---------------------------------------------------------------------------
    // Custom
    // ---------------------------------------------------------------------------

    @Test
    fun `Custom forwards wasmExport, the armed opcode, notification bytes, and elapsedMs to the invoker`() {
        var seenExport: String? = null
        var seenBytes: ByteArray? = null
        var seenOpcode: Int? = null
        var seenElapsedMs: Long? = null
        val strategy = EndOfStreamStrategy.Custom("myPredicate") { wasmExport, bytes, opcode, elapsedMs ->
            seenExport = wasmExport
            seenBytes = bytes
            seenOpcode = opcode
            seenElapsedMs = elapsedMs
            true
        }
        strategy.onStreamStart(0x55)
        val result = strategy.onNotification(byteArrayOf(0x01, 0x02), 1234L)

        assertTrue(result)
        assertEquals("myPredicate", seenExport)
        assertEquals(0x55, seenOpcode)
        assertEquals(1234L, seenElapsedMs)
        assertTrue(byteArrayOf(0x01, 0x02).contentEquals(seenBytes))
    }

    @Test
    fun `Custom returns false when the invoker returns false`() {
        val strategy = EndOfStreamStrategy.Custom("myPredicate") { _, _, _, _ -> false }
        strategy.onStreamStart(0)
        assertFalse(strategy.onNotification(byteArrayOf(0x01), 0))
    }

    @Test
    fun `Custom uses the latest onStreamStart opcode, not a stale one`() {
        var seenOpcode: Int? = null
        val strategy = EndOfStreamStrategy.Custom("myPredicate") { _, _, opcode, _ ->
            seenOpcode = opcode
            false
        }
        strategy.onStreamStart(0x55)
        strategy.onStreamStart(0x22) // re-armed for a different command reusing this instance
        strategy.onNotification(byteArrayOf(), 0)
        assertEquals(0x22, seenOpcode)
    }

    @Test
    fun `factory decodes CUSTOM params and wires the supplied customInvoker`() {
        val params = buildJsonObject {
            put("wasmExport", JsonPrimitive("myPredicate"))
        }
        val aes = AwaitEndOfStream(characteristic = "notify", strategy = "CUSTOM", params = params)
        var invoked = false
        val strategy = EndOfStreamStrategyFactory.from(aes) { wasmExport, _, _, _ ->
            invoked = true
            wasmExport == "myPredicate"
        }
        assertTrue(strategy is EndOfStreamStrategy.Custom)
        assertEquals("myPredicate", (strategy as EndOfStreamStrategy.Custom).wasmExport)
        assertTrue(strategy.onNotification(byteArrayOf(), 0))
        assertTrue(invoked)
    }

    @Test
    fun `factory falls back to TimeoutOnly for CUSTOM missing wasmExport`() {
        val aes = AwaitEndOfStream(characteristic = "notify", strategy = "CUSTOM", params = buildJsonObject { })
        val strategy = EndOfStreamStrategyFactory.from(aes) { _, _, _, _ -> true }
        assertTrue(strategy is EndOfStreamStrategy.TimeoutOnly)
    }

    @Test
    fun `factory falls back to TimeoutOnly for CUSTOM when no customInvoker is supplied`() {
        val params = buildJsonObject {
            put("wasmExport", JsonPrimitive("myPredicate"))
        }
        val aes = AwaitEndOfStream(characteristic = "notify", strategy = "CUSTOM", params = params)
        // No customInvoker argument — mirrors every other call site in this file/production
        // code that doesn't need one.
        assertTrue(EndOfStreamStrategyFactory.from(aes) is EndOfStreamStrategy.TimeoutOnly)
    }

    // ---------------------------------------------------------------------------
    // TimeoutOnly
    // ---------------------------------------------------------------------------

    @Test
    fun `TimeoutOnly never completes`() {
        assertFalse(EndOfStreamStrategy.TimeoutOnly.onNotification(byteArrayOf(0x01, 0x02), 0))
    }

    @Test
    fun `factory falls back to TimeoutOnly for an unrecognized strategy tag`() {
        val aes = AwaitEndOfStream(characteristic = "notify", strategy = "NOT_A_REAL_STRATEGY")
        assertTrue(EndOfStreamStrategyFactory.from(aes) is EndOfStreamStrategy.TimeoutOnly)
    }

    // ---------------------------------------------------------------------------
    // Factory / JSON round-trip
    // ---------------------------------------------------------------------------

    @Test
    fun `factory decodes SENTINEL_PACKET params`() {
        val params = buildJsonObject {
            put("terminatorByte", JsonPrimitive(255))
            put("matchOpcode", JsonPrimitive(true))
        }
        val aes = AwaitEndOfStream(characteristic = "notify", strategy = "SENTINEL_PACKET", params = params)
        val strategy = EndOfStreamStrategyFactory.from(aes)
        assertTrue(strategy is EndOfStreamStrategy.SentinelPacket)
        strategy as EndOfStreamStrategy.SentinelPacket
        assertEquals(255, strategy.terminatorByte)
        assertTrue(strategy.matchOpcode)
    }

    @Test
    fun `factory decodes IN_STREAM_TERMINATOR params`() {
        val params = buildJsonObject {
            put("terminatorBytes", JsonPrimitive("0xFF 0xFF"))
        }
        val aes = AwaitEndOfStream(characteristic = "notify", strategy = "IN_STREAM_TERMINATOR", params = params)
        val strategy = EndOfStreamStrategyFactory.from(aes)
        assertTrue(strategy is EndOfStreamStrategy.InStreamTerminator)
        assertEquals(
            EndOfStreamStrategy.InStreamTerminator(byteArrayOf(0xFF.toByte(), 0xFF.toByte())),
            strategy,
        )
    }

    // unlike every sibling branch's *OrNull-based degrade, IN_STREAM_TERMINATOR used to
    // call parseHexBytes's unguarded .toInt(16) directly, throwing NumberFormatException out of
    // from() on malformed hex instead of falling back to TimeoutOnly.
    @Test
    fun `factory falls back to TimeoutOnly for malformed IN_STREAM_TERMINATOR hex`() {
        val params = buildJsonObject {
            put("terminatorBytes", JsonPrimitive("not-hex"))
        }
        val aes = AwaitEndOfStream(characteristic = "notify", strategy = "IN_STREAM_TERMINATOR", params = params)
        assertTrue(EndOfStreamStrategyFactory.from(aes) is EndOfStreamStrategy.TimeoutOnly)
    }

    @Test
    fun `factory decodes IN_STREAM_TERMINATOR matchOpcode params`() {
        val params = buildJsonObject {
            put("matchOpcode", JsonPrimitive(true))
            put("terminatorSuffix", JsonPrimitive("0xFF"))
        }
        val aes = AwaitEndOfStream(characteristic = "notify", strategy = "IN_STREAM_TERMINATOR", params = params)
        val strategy = EndOfStreamStrategyFactory.from(aes)
        assertTrue(strategy is EndOfStreamStrategy.InStreamTerminator)
        strategy.onStreamStart(0x66)
        assertTrue(strategy.onNotification(byteArrayOf(0x01, 0x02, 0x66, 0xFF.toByte()), 0))
    }

    @Test
    fun `factory falls back to TimeoutOnly for IN_STREAM_TERMINATOR matchOpcode=true missing terminatorSuffix`() {
        val params = buildJsonObject {
            put("matchOpcode", JsonPrimitive(true))
        }
        val aes = AwaitEndOfStream(characteristic = "notify", strategy = "IN_STREAM_TERMINATOR", params = params)
        assertTrue(EndOfStreamStrategyFactory.from(aes) is EndOfStreamStrategy.TimeoutOnly)
    }

    @Test
    fun `full SyncCommand list with a strategy-tagged awaitEndOfStream decodes end-to-end`() {
        val jsonStr = """
            [{"type":"WRITE","characteristic":"write","bytes":"0x55 0x00","awaitEndOfStream":{"characteristic":"notify","strategy":"QUIET_PERIOD","params":{"quietMs":1500},"timeoutMs":10000}}]
        """.trimIndent()
        val commands = json.decodeFromString<List<SyncCommand>>(jsonStr)
        assertEquals(1, commands.size)

        val write = commands[0] as SyncCommand.Write
        val aes = write.awaitEndOfStream
        assertEquals("QUIET_PERIOD", aes?.strategy)

        val strategy = EndOfStreamStrategyFactory.from(aes!!)
        assertTrue(strategy is EndOfStreamStrategy.QuietPeriod)
        assertEquals(1500L, (strategy as EndOfStreamStrategy.QuietPeriod).quietMs)
    }
}
