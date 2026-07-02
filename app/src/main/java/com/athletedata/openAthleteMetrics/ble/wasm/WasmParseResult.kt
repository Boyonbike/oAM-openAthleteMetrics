package com.athletedata.openAthleteMetrics.ble.wasm

import com.athletedata.openAthleteMetrics.data.model.MetricReading

sealed class WasmParseResult { // WASM-RESULT
    data class Success(val readings: List<MetricReading>) : WasmParseResult() // WASM-RESULT
    object Empty : WasmParseResult() // WASM-RESULT
    data class WasmTrap(val message: String) : WasmParseResult() // WASM-RESULT
    data class DeserialisationError(val message: String) : WasmParseResult() // WASM-RESULT
    object EngineNotInitialised : WasmParseResult() // WASM-RESULT
}
