package com.athletedata.openAthleteMetrics.ble.sync

import com.athletedata.openAthleteMetrics.data.model.SyncStatus

data class SyncSummary(
    val readingsAccepted: Int,
    val readingsRejected: Int,
    val sessionsAccepted: Int,
    val sessionsRejected: Int,
    val activitiesAccepted: Int,
    val activitiesRejected: Int,
    val rejectionReasons: List<String>,
    val finalStatus: SyncStatus,
)
