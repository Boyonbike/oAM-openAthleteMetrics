package com.athletedata.openAthleteMetrics.ble

sealed class BleCommand {
    data class Write(val characteristic: String, val bytes: String) : BleCommand()
    data class EnableNotify(val characteristic: String) : BleCommand()
    data class Delay(val millis: Long) : BleCommand()
}
