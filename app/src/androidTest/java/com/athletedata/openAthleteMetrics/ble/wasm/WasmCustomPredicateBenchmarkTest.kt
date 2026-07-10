package com.athletedata.openAthleteMetrics.ble.wasm

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dylibso.chicory.runtime.ExecutionListener
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.runtime.MStack
import com.dylibso.chicory.wasm.Parser
import com.dylibso.chicory.wasm.types.Instruction
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not run automatically — this is a manual benchmark for follow-up E2 (the on-device
 * companion to the JVM-only measurement recorded in WasmDriverEngine's
 * CUSTOM_PREDICATE_BUDGET_MS comment). Run it on real Android/ART hardware via the
 * Instrumentation test runner (`connectedDebugAndroidTest` or the IDE's "Run" gutter
 * action) and read the logged ns/call figures from logcat/stdout. It intentionally makes
 * no assertions about a specific timing number and does not feed back into
 * CUSTOM_PREDICATE_BUDGET_MS on its own — that still requires a human to look at the
 * result and decide.
 *
 * Mirrors, rather than reuses, WasmDriverEngine's private ensureCustomInstance /
 * BudgetExecutionListener: builds a dedicated Instance with a per-instruction
 * ExecutionListener attached, over a trivial predicate export, timed the same way the
 * JVM follow-up E2 measurement was structured (warm up first, then multiple timed
 * trials).
 */
@RunWith(AndroidJUnit4::class)
class WasmCustomPredicateBenchmarkTest {

    // A minimal module exporting a single function matching the Custom predicate ABI
    // ((ptr, len, opcode, elapsedMs) -> i32), compiled from:
    //   (module
    //     (memory (export "memory") 1)
    //     (func $trivialPredicate (param i32 i32 i32 i64) (result i32)
    //       i32.const 1)
    //     (export "trivialPredicate" (func $trivialPredicate)))
    private val trivialPredicateWasm = byteArrayOf(
        0, 97, 115, 109, 1, 0, 0, 0, 1, 9, 1, 96, 4, 127, 127, 127, 126, 1, 127, 3, 2, 1, 0, 5, 3, 1, 0, 1, 7, 29, 2,
        6, 109, 101, 109, 111, 114, 121, 2, 0, 16, 116, 114, 105, 118, 105, 97, 108, 80, 114, 101, 100, 105, 99, 97,
        116, 101, 0, 0, 10, 6, 1, 4, 0, 65, 1, 11,
    )

    private class CountingExecutionListener : ExecutionListener {
        var instructionCount: Long = 0
        override fun onExecution(instruction: Instruction, stack: MStack) {
            instructionCount++
        }
    }

    @Test
    fun benchmarkTrivialPredicateCallOverhead() {
        val module = Parser.parse(trivialPredicateWasm)
        val listener = CountingExecutionListener()
        val instance = Instance.builder(module).withUnsafeExecutionListener(listener).build()
        val predicate = instance.export("trivialPredicate")

        val warmupCalls = 2_000
        val timedCalls = 10_000

        repeat(warmupCalls) {
            predicate.apply(0L, 0L, 0L, 0L)
        }

        val instructionsBefore = listener.instructionCount
        val startNanos = System.nanoTime()
        repeat(timedCalls) {
            predicate.apply(0L, 0L, 0L, 0L)
        }
        val elapsedNanos = System.nanoTime() - startNanos
        val instructionsExecuted = listener.instructionCount - instructionsBefore

        val nsPerCall = elapsedNanos.toDouble() / timedCalls
        val nsPerInstruction = if (instructionsExecuted > 0) elapsedNanos.toDouble() / instructionsExecuted else Double.NaN

        println(
            "WasmCustomPredicateBenchmarkTest: $timedCalls calls, " +
                "$instructionsExecuted listener-observed instructions, " +
                "%.1f ns/call, %.2f ns/instruction (on-device, warmed up)".format(nsPerCall, nsPerInstruction),
        )
    }
}
