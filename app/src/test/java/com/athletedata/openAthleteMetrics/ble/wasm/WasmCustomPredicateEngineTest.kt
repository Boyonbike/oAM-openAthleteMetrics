package com.athletedata.openAthleteMetrics.ble.wasm

import com.athletedata.openAthleteMetrics.ble.SyncContextSerializer
import com.athletedata.openAthleteMetrics.ble.driver.BleConfig
import com.athletedata.openAthleteMetrics.ble.driver.EndOfStreamStrategy
import com.athletedata.openAthleteMetrics.ble.driver.MatchConfidence
import com.athletedata.openAthleteMetrics.ble.driver.ParsingConfig
import com.athletedata.openAthleteMetrics.ble.driver.WasmDriverManifest
import com.athletedata.openAthleteMetrics.ble.driver.WasmExports
import com.athletedata.openAthleteMetrics.data.model.MetricType
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Tests [EndOfStreamStrategy.Custom]'s engine-side call path — [WasmDriverEngine.evaluateCustomPredicate] —
 * against a real, hand-encoded WASM module.
 *
 * The test module is hand-encoded from the following WAT (no WAT toolchain is available in
 * this repo/environment, confirmed while investigating the execution-budget mechanism this
 * feature relies on, so this is a raw binary built by hand rather than compiled):
 * ```wat
 * (module
 *   (memory (export "memory") 1)
 *   (func (export "predicate") (param i32 i32 i32 i32) (result i32)
 *     local.get 0
 *     i32.load8_u
 *     i32.const 42
 *     i32.eq)
 *   (func (export "spin")
 *     loop
 *       i32.const 1
 *       br_if 0
 *     end)
 *   (func (export "returnsRaw") (param i32 i32 i32 i32) (result i32)
 *     local.get 2)
 *   (func (export "oobRead") (param i32 i32 i32 i32) (result i32)
 *     local.get 0
 *     local.get 1
 *     i32.add
 *     i32.const 65536
 *     i32.add
 *     i32.load8_u)
 *   (func (export "flaky") (param i32 i32 i32 i32) (result i32)
 *     local.get 0
 *     i32.load8_u
 *     i32.const 255
 *     i32.eq
 *     if
 *       unreachable
 *     end
 *     local.get 0
 *     i32.load8_u
 *     i32.const 42
 *     i32.eq)
 *   (func (export "alwaysTrap")
 *     unreachable)
 * )
 * ```
 * `predicate` returns 1 iff the byte at `ptr` (its first param) equals 0x2A (42) — a stand-in
 * sentinel value; it ignores `len`/`opcode`/`elapsedMs`. `spin` is a `loop/br_if/end`-only
 * infinite loop, deliberately with no plain `br` and no calls — the one shape Chicory's
 * `Thread.interrupt()`-based `checkInterruption()` does not catch, since that check only runs
 * at `CALL`, `CALL_INDIRECT`, and unconditional `BR`. It exists here to prove the
 * execution-budget `ExecutionListener` (not thread-interrupt) is what actually bounds a
 * CUSTOM predicate call.
 *
 * The remaining four exports model adversarial driver behavior for a diagnostic safety review
 * of the CUSTOM strategy: `returnsRaw` echoes the `opcode` param unchanged so a single export
 * can exercise "predicate returns something other than 0/1" from the Kotlin side; `oobRead`
 * reads at `ptr + len + 65536`, always out of bounds for this module's single 64KB memory page
 * regardless of the `ptr`/`len` supplied, modeling a driver that reads past the notification's
 * declared length; `flaky` behaves like `predicate` except it traps on a magic trigger byte
 * (0xFF), modeling a driver bug that only manifests for certain input data rather than every
 * call; `alwaysTrap` traps unconditionally. All four share the same `Instance` as `predicate`
 * and `spin` (the engine caches its dedicated CUSTOM-predicate Instance per manifestId, not per
 * export), which is what makes the cross-export isolation tests below meaningful.
 */
class WasmCustomPredicateEngineTest {

    private val testWasmBytes: ByteArray = (
        "00 61 73 6d 01 00 00 00" +
            // type section: [(i32 i32 i32 i32) -> i32, () -> ()]
            "01 0c 02 60 04 7f 7f 7f 7f 01 7f 60 00 00" +
            // function section: predicate,spin,returnsRaw,oobRead,flaky,alwaysTrap
            "03 07 06 00 01 00 00 00 01" +
            // memory section: 1 memory, min 1 page
            "05 03 01 00 01" +
            // export section: memory, predicate, spin, returnsRaw, oobRead, flaky, alwaysTrap
            "07 49 07" +
            "06 6d 65 6d 6f 72 79 02 00" +
            "09 70 72 65 64 69 63 61 74 65 00 00" +
            "04 73 70 69 6e 00 01" +
            "0a 72 65 74 75 72 6e 73 52 61 77 00 02" +
            "07 6f 6f 62 52 65 61 64 00 03" +
            "05 66 6c 61 6b 79 00 04" +
            "0a 61 6c 77 61 79 73 54 72 61 70 00 05" +
            // code section: predicate, spin, returnsRaw, oobRead, flaky, alwaysTrap
            "0a 47 06" +
            "0a 00 20 00 2d 00 00 41 2a 46 0b" +
            "09 00 03 40 41 01 0d 00 0b 0b" +
            "04 00 20 02 0b" +
            "0f 00 20 00 20 01 6a 41 80 80 04 6a 2d 00 00 0b" +
            "17 00 20 00 2d 00 00 41 ff 01 46 04 40 00 0b 20 00 2d 00 00 41 2a 46 0b" +
            "03 00 00 0b"
        ).replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun manifest(id: String = "custom-predicate-test") = WasmDriverManifest(
        id = id,
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
        parsing = ParsingConfig.WasmParsing(
            wasmBytes = testWasmBytes,
            exports = WasmExports(),
        ),
        specVersion = "2",
    )

    @Test
    fun `evaluateCustomPredicate resolves true on a matching notification byte`() = runBlocking {
        val engine = WasmDriverEngine(SyncContextSerializer())
        val manifest = manifest()
        assertTrue("test WASM module failed to load — check the hand-encoded bytes", engine.load(manifest))

        val result = engine.evaluateCustomPredicate(
            manifestId = manifest.id,
            wasmExport = "predicate",
            bytes = byteArrayOf(0x2A),
            opcode = 0x55,
            elapsedMs = 0L,
        )

        assertTrue(result)
    }

    @Test
    fun `evaluateCustomPredicate resolves false on a non-matching notification byte`() = runBlocking {
        val engine = WasmDriverEngine(SyncContextSerializer())
        val manifest = manifest()
        assertTrue(engine.load(manifest))

        val result = engine.evaluateCustomPredicate(
            manifestId = manifest.id,
            wasmExport = "predicate",
            bytes = byteArrayOf(0x01),
            opcode = 0x55,
            elapsedMs = 0L,
        )

        assertFalse(result)
    }

    @Test(timeout = 5000)
    fun `evaluateCustomPredicate aborts a br_if-only busy loop via the execution-budget listener and returns false`() = runBlocking {
        val engine = WasmDriverEngine(SyncContextSerializer())
        val manifest = manifest()
        assertTrue(engine.load(manifest))

        // "spin" never returns on its own. If the execution-budget ExecutionListener didn't
        // actually abort it (e.g. if this relied on Thread.interrupt() instead, which does not
        // fire for this loop shape), this call would hang past the @Test timeout above instead
        // of returning false promptly.
        val result = engine.evaluateCustomPredicate(
            manifestId = manifest.id,
            wasmExport = "spin",
            bytes = byteArrayOf(0x2A),
            opcode = 0x55,
            elapsedMs = 0L,
        )

        assertFalse(result)
    }

    @Test
    fun `evaluateCustomPredicate returns false for an unknown export without crashing`() = runBlocking {
        val engine = WasmDriverEngine(SyncContextSerializer())
        val manifest = manifest()
        assertTrue(engine.load(manifest))

        val result = engine.evaluateCustomPredicate(
            manifestId = manifest.id,
            wasmExport = "doesNotExist",
            bytes = byteArrayOf(0x2A),
            opcode = 0x55,
            elapsedMs = 0L,
        )

        assertFalse(result)
    }

    @Test
    fun `evaluateCustomPredicate returns false when manifestId does not match the currently loaded driver`() = runBlocking {
        val engine = WasmDriverEngine(SyncContextSerializer())
        val manifest = manifest()
        assertTrue(engine.load(manifest))

        val result = engine.evaluateCustomPredicate(
            manifestId = "some-other-driver",
            wasmExport = "predicate",
            bytes = byteArrayOf(0x2A),
            opcode = 0x55,
            elapsedMs = 0L,
        )

        assertFalse(result)
    }

    // -------------------------------------------------------------------------
    // Adversarial safety verification (diagnostic session): a misbehaving CUSTOM
    // predicate must never crash, hang, or falsely resolve the stream as ended.
    // -------------------------------------------------------------------------

    @Test
    fun `evaluateCustomPredicate returns false when the predicate traps unconditionally`() = runBlocking {
        val engine = WasmDriverEngine(SyncContextSerializer())
        val manifest = manifest()
        assertTrue(engine.load(manifest))

        val result = engine.evaluateCustomPredicate(
            manifestId = manifest.id,
            wasmExport = "alwaysTrap",
            bytes = byteArrayOf(0x2A),
            opcode = 0x55,
            elapsedMs = 0L,
        )

        assertFalse(result)
    }

    @Test
    fun `evaluateCustomPredicate returnsRaw treats any non-1 opcode as not-ended`() = runBlocking {
        val engine = WasmDriverEngine(SyncContextSerializer())
        val manifest = manifest()
        assertTrue(engine.load(manifest))

        for (opcode in listOf(-1, 2, Int.MAX_VALUE)) {
            val result = engine.evaluateCustomPredicate(
                manifestId = manifest.id,
                wasmExport = "returnsRaw",
                bytes = byteArrayOf(0x2A),
                opcode = opcode,
                elapsedMs = 0L,
            )
            assertFalse("opcode $opcode should not resolve the stream as ended", result)
        }
    }

    @Test
    fun `evaluateCustomPredicate returnsRaw resolves true when opcode is exactly 1 (sanity control)`() = runBlocking {
        val engine = WasmDriverEngine(SyncContextSerializer())
        val manifest = manifest()
        assertTrue(engine.load(manifest))

        val result = engine.evaluateCustomPredicate(
            manifestId = manifest.id,
            wasmExport = "returnsRaw",
            bytes = byteArrayOf(0x2A),
            opcode = 1,
            elapsedMs = 0L,
        )

        assertTrue(result)
    }

    @Test
    fun `evaluateCustomPredicate returns false for an out-of-bounds memory read without crashing`() = runBlocking {
        val engine = WasmDriverEngine(SyncContextSerializer())
        val manifest = manifest()
        assertTrue(engine.load(manifest))

        val result = engine.evaluateCustomPredicate(
            manifestId = manifest.id,
            wasmExport = "oobRead",
            bytes = byteArrayOf(0x2A),
            opcode = 0x55,
            elapsedMs = 0L,
        )

        assertFalse(result)
    }

    @Test
    fun `evaluateCustomPredicate flaky predicate - correct results before and after a trap, with cross-export isolation`() = runBlocking {
        val engine = WasmDriverEngine(SyncContextSerializer())
        val manifest = manifest()
        assertTrue(engine.load(manifest))

        fun flaky(byte: Int) = engine.evaluateCustomPredicate(
            manifestId = manifest.id,
            wasmExport = "flaky",
            bytes = byteArrayOf(byte.toByte()),
            opcode = 0x55,
            elapsedMs = 0L,
        )

        assertTrue("normal matching byte before any trap", flaky(0x2A))
        assertFalse("normal non-matching byte", flaky(0x01))
        assertFalse("magic trigger byte traps but must not crash", flaky(0xFF))
        assertTrue(
            "a call right after the trap must not be corrupted by leftover state",
            flaky(0x2A),
        )

        val predicateResult = engine.evaluateCustomPredicate(
            manifestId = manifest.id,
            wasmExport = "predicate",
            bytes = byteArrayOf(0x2A),
            opcode = 0x55,
            elapsedMs = 0L,
        )
        assertTrue(
            "a different export sharing the same Instance must be unaffected by flaky's trap",
            predicateResult,
        )
    }

    @Test(timeout = 5000)
    fun `EndOfStreamStrategy Custom wired to a real busy-loop predicate never throws past onNotification`() = runBlocking {
        val engine = WasmDriverEngine(SyncContextSerializer())
        val manifest = manifest()
        assertTrue(engine.load(manifest))

        val strategy = EndOfStreamStrategy.Custom(
            wasmExport = "spin",
            invoke = { wasmExport, bytes, opcode, elapsedMs ->
                engine.evaluateCustomPredicate(manifest.id, wasmExport, bytes, opcode, elapsedMs)
            },
        )
        strategy.onStreamStart(opcode = 0x55)

        // If evaluateCustomPredicate ever let an exception escape (budget-exceeded, trap, or
        // otherwise), it would propagate straight through Custom.onNotification — which has no
        // try/catch of its own — and this call would throw instead of returning false.
        val result = strategy.onNotification(byteArrayOf(0x2A), elapsedMs = 0L)

        assertFalse(result)
    }

    @Test
    fun `evaluateCustomPredicate is not falsely aborted by leftover budget state after a timed-out spin call`() = runBlocking {
        val engine = WasmDriverEngine(SyncContextSerializer())
        val manifest = manifest()
        assertTrue(engine.load(manifest))

        val spinResult = engine.evaluateCustomPredicate(
            manifestId = manifest.id,
            wasmExport = "spin",
            bytes = byteArrayOf(0x2A),
            opcode = 0x55,
            elapsedMs = 0L,
        )
        assertFalse(spinResult)

        val fastResult = engine.evaluateCustomPredicate(
            manifestId = manifest.id,
            wasmExport = "predicate",
            bytes = byteArrayOf(0x2A),
            opcode = 0x55,
            elapsedMs = 0L,
        )
        assertTrue(
            "a fast, correct call right after a budget-exceeded call must not be falsely aborted",
            fastResult,
        )
    }

    @Test
    fun `evaluateCustomPredicate handles a tight burst of alternating notifications correctly and quickly`() = runBlocking {
        val engine = WasmDriverEngine(SyncContextSerializer())
        val manifest = manifest()
        assertTrue(engine.load(manifest))

        val startMs = System.currentTimeMillis()
        repeat(100) { i ->
            val matching = i % 2 == 0
            val result = engine.evaluateCustomPredicate(
                manifestId = manifest.id,
                wasmExport = "predicate",
                bytes = byteArrayOf(if (matching) 0x2A else 0x01),
                opcode = 0x55,
                elapsedMs = 0L,
            )
            assertTrue("call #$i (matching=$matching) was falsely killed by the budget mechanism", result == matching)
        }
        val elapsedMs = System.currentTimeMillis() - startMs

        assertTrue(
            "100 fast correct calls took ${elapsedMs}ms - budget-state bookkeeping may be accumulating overhead",
            elapsedMs < 2000,
        )
    }

    @Test
    fun `a failing CUSTOM predicate for one command does not corrupt a different command's predicate sharing the same Instance`() = runBlocking {
        val engine = WasmDriverEngine(SyncContextSerializer())
        val manifest = manifest()
        assertTrue(engine.load(manifest))

        // Simulates one sync command's CUSTOM predicate (a different wasmExport than the one
        // used above) misbehaving via a trap, not a budget timeout — exercising a different
        // failure path than the spin/timeout case above while still sharing the same
        // manifestId-keyed Instance and BudgetExecutionListener.
        val flakyResult = engine.evaluateCustomPredicate(
            manifestId = manifest.id,
            wasmExport = "flaky",
            bytes = byteArrayOf(0xFF.toByte()),
            opcode = 0x55,
            elapsedMs = 0L,
        )
        assertFalse(flakyResult)

        val otherCommandResult = engine.evaluateCustomPredicate(
            manifestId = manifest.id,
            wasmExport = "predicate",
            bytes = byteArrayOf(0x2A),
            opcode = 0x55,
            elapsedMs = 0L,
        )
        assertTrue(
            "a different sync command's CUSTOM predicate must not be corrupted by an earlier trap on another export",
            otherCommandResult,
        )
    }
}
