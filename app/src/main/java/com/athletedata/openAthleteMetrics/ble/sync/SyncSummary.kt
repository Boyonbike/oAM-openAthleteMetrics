package com.athletedata.openAthleteMetrics.ble.sync

import com.athletedata.openAthleteMetrics.data.model.SyncStatus

data class SyncSummary(
    val newRecordsInserted: Int,
    val accumulatorUpdates: Int,
    val accumulatorNoChange: Int,
    val accumulatorGuarded: Int,
    val readingsSkipped: Int,
    val sessionsInserted: Int,
    val activitiesInserted: Int,
    val activitiesSkipped: Int,
    val finalStatus: SyncStatus,
    val packetsReceived: Int = 0,
    val syncedBeforeQuiescence: Boolean = false,
    val rejectionReasons: List<String> = emptyList(),
)
