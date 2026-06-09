package com.athletedata.openAthleteMetrics.ble.wasm

import com.dylibso.chicory.wasm.Parser

object WasmRuntimeCheck {
    fun verify(): Boolean {
        return try {
            val minimalWasm = byteArrayOf(
                0x00, 0x61, 0x73, 0x6d, // magic: \0asm
                0x01, 0x00, 0x00, 0x00  // version: 1
            )
            Parser.parse(minimalWasm)
            true
        } catch (e: Exception) {
            false
        }
    }
}
