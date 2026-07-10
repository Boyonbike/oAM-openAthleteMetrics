package com.athletedata.openAthleteMetrics.ble.wasm

import com.dylibso.chicory.wasm.Parser
import com.dylibso.chicory.wasm.types.ActiveDataSegment
import com.dylibso.chicory.wasm.types.OpCode
import junit.framework.TestCase.assertTrue
import org.junit.Test

/**
 * Standing regression guard for hume_band.wat's hand-tracked memory map. A prior audit found and
 * fixed a real silent data-segment collision caused by manually re-deriving addresses after
 * growing an earlier segment's template — this parses the *compiled* hume_band.wasm's actual
 * data section via Chicory's public API (validating the real shipped artifact, not just the
 * .wat source text) and asserts no two segments overlap, so a future template-size change can't
 * silently reintroduce the same bug class.
 */
class WasmHumeBandMemoryLayoutTest {

    private fun loadWasmBytes(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("wasm/hume_band.wasm")!!.readBytes()

    private data class Segment(val start: Long, val length: Long) {
        val end: Long get() = start + length
    }

    @Test
    fun `no two active data segments overlap in linear memory`() {
        val module = Parser.parse(loadWasmBytes())
        val segments = module.dataSection().dataSegments().mapIndexed { i, seg ->
            require(seg is ActiveDataSegment) {
                "segment $i is not an ActiveDataSegment -- update this test if hume_band.wat starts using passive segments"
            }
            val offsetInstr = seg.offsetInstructions().single()
            require(offsetInstr.opcode() == OpCode.I32_CONST) {
                "segment $i's offset expr is ${offsetInstr.opcode()}, expected I32_CONST -- update this test"
            }
            Segment(start = offsetInstr.operand(0), length = seg.data().size.toLong())
        }

        assertTrue("expected at least the 7 known static-table segments, found ${segments.size}", segments.size >= 7)

        val sorted = segments.sortedBy { it.start }
        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            assertTrue(
                "data segment overlap: [0x%X, 0x%X) and [0x%X, 0x%X)".format(a.start, a.end, b.start, b.end),
                a.end <= b.start,
            )
        }

        // Hand-tracked scratch regions from hume_band.wat's memory-map comment (not data
        // segments, so not covered above) -- keep in sync with that comment if it changes.
        val reserved = listOf(
            Segment(start = 0x3FC00, length = 0x400), // B64_SCRATCH
            Segment(start = 0x40800, length = 0xA0), // int-to-string/YMD/cmd-bytes scratch
        )
        for (r in reserved) {
            for (seg in sorted) {
                assertTrue(
                    "reserved scratch [0x%X, 0x%X) overlaps data segment [0x%X, 0x%X)"
                        .format(r.start, r.end, seg.start, seg.end),
                    seg.end <= r.start || r.end <= seg.start,
                )
            }
        }
    }
}
