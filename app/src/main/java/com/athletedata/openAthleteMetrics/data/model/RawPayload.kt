package com.athletedata.openAthleteMetrics.data.model

import java.time.Instant

/**
 * An unprocessed byte buffer received from a single BLE characteristic.
 *
 * Drivers include these in [DriverSyncResult.rawPayloads] so the validation
 * layer (or a future debug tool) can log or reparse raw device output without
 * re-connecting to the device.
 *
 * @property characteristicUuid  The full UUID of the GATT characteristic this payload came from.
 * @property payload             Raw bytes exactly as received over BLE — no decoding applied.
 * @property receivedAt          Wall-clock time when the BLE callback delivered this payload.
 */
data class RawPayload(
    val characteristicUuid: String,
    val payload: ByteArray,
    val receivedAt: Instant,
) {
    // ByteArray uses reference equality by default — override to give data class semantics.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawPayload) return false
        return characteristicUuid == other.characteristicUuid &&
            payload.contentEquals(other.payload) &&
            receivedAt == other.receivedAt
    }

    override fun hashCode(): Int {
        var result = characteristicUuid.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + receivedAt.hashCode()
        return result
    }
}
