package com.athletedata.openAthleteMetrics.ble

import com.athletedata.openAthleteMetrics.ble.sync.SyncSummary

sealed class BleConnectionState {
    object Idle : BleConnectionState()
    object Scanning : BleConnectionState()
    data class Connecting(val deviceAddress: String) : BleConnectionState()
    data class Connected(
        val deviceAddress: String,
        val driverName: String,
        val packetsReceived: Int = 0,
        val isQuiescent: Boolean = false,
    ) : BleConnectionState()
    data class Syncing(val deviceAddress: String, val progress: Float) : BleConnectionState()
    data class SyncComplete(val summary: SyncSummary, val deviceAddress: String) : BleConnectionState()
    data class Disconnected(val deviceAddress: String, val reason: String?) : BleConnectionState()
    data class Error(val message: String) : BleConnectionState()
    data class GattCacheError(val deviceAddress: String, val deviceName: String) : BleConnectionState()
}
