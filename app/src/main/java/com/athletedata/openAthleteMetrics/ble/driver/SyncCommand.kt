package com.athletedata.openAthleteMetrics.ble.driver

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

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
