package com.athletedata.openAthleteMetrics.ble.driver

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonObject

// byte-position match condition used by AwaitEndOfStream to detect the terminal packet.
@Serializable
data class EndByteCondition(
    val offset: Int,
    val value: Int,
)

// suspend a WRITE command until a notification arrives on a named characteristic.
@Serializable
data class AwaitReply(
    val characteristicRole: String,
    val timeoutMs: Long = 5000L,
)

// suspend a WRITE until a notification whose payload satisfies endByte arrives, or timeout elapses.
// Declares a pluggable EndOfStreamStrategy (see EndOfStreamStrategy.kt). Missing strategy falls
// back to FixedOffsetByte using endByte.
@Serializable
data class AwaitEndOfStream(
    val characteristic: String,
    val endByte: EndByteCondition? = null, // optional — only required by FixedOffsetByte
    val timeoutMs: Long = 30_000L,
    val strategy: String? = null,
    val params: JsonObject? = null,
)

/**
 * A single step in the sync sequence sent to the device after connecting.
 *
 * Commands are executed in order. The JSON `"type"` field selects the subclass:
 * - `"WRITE"` — write a byte sequence to a characteristic
 * - `"ENABLE_NOTIFY"` — subscribe to notifications on a characteristic
 * - `"DELAY"` — pause for a fixed duration before continuing
 */
@Serializable
@JsonClassDiscriminator("type")
sealed class SyncCommand {

    /**
     * Writes [bytes] to the characteristic identified by [characteristic] (a role name from
     * [BleConfig.characteristics]). [bytes] is a hex string, e.g. `"0x01 0xAB 0x00"`.
     */
    @Serializable
    @SerialName("WRITE")
    data class Write(
        val characteristic: String,
        val bytes: String,
        val awaitReply: AwaitReply? = null,
        val awaitEndOfStream: AwaitEndOfStream? = null,
    ) : SyncCommand()

    /**
     * Enables notifications on the characteristic identified by [characteristic]
     * (a role name from [BleConfig.characteristics]).
     */
    @Serializable
    @SerialName("ENABLE_NOTIFY")
    data class EnableNotify(
        val characteristic: String,
    ) : SyncCommand()

    /**
     * Waits [millis] milliseconds before executing the next command.
     * Useful when a device needs time to prepare data after a WRITE.
     */
    @Serializable
    @SerialName("DELAY")
    data class Delay(
        val millis: Long,
    ) : SyncCommand()
}
