package com.athletedata.openAthleteMetrics.ble.wasm

/**
 * A single buffered BLE notification captured during a sync session.
 *
 * [opcode] is always byte 0 of [bytes] formatted as `"0xNN"`, pre-extracted so the WASM
 * author can route on opcode without re-parsing the byte array.
 */
data class SessionFrame(
    val characteristic: String,
    val opcode: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SessionFrame) return false
        return characteristic == other.characteristic &&
            opcode == other.opcode &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = characteristic.hashCode()
        result = 31 * result + opcode.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}
