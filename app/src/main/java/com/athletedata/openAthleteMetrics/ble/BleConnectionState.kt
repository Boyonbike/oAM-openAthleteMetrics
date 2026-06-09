package com.athletedata.openAthleteMetrics.ble

import com.athletedata.openAthleteMetrics.ble.sync.SyncSummary

sealed class BleConnectionState {
    object Idle : BleConnectionState()
    object Scanning : BleConnectionState()
    data class Connecting(val deviceAddress: String) : BleConnectionState()
    data class Connected(val deviceAddress: String, val driverName: String) : BleConnectionState()
    data class Syncing(val deviceAddress: String, val progress: Float) : BleConnectionState()
    data class SyncComplete(val summary: SyncSummary, val deviceAddress: String) : BleConnectionState()
    data class Disconnected(val deviceAddress: String, val reason: String?) : BleConnectionState()
    data class Error(val message: String) : BleConnectionState()
}
