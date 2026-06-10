package com.athletedata.openAthleteMetrics.ble.sync

import com.athletedata.openAthleteMetrics.data.model.SyncStatus

data class SyncSummary(
    val readingsAccepted: Int,
    val readingsRejected: Int,
    val readingsSkipped: Int,
    val sessionsAccepted: Int,
    val sessionsRejected: Int,
    val sessionsSkipped: Int,
    val activitiesAccepted: Int,
    val activitiesRejected: Int,
    val activitiesSkipped: Int,
    val rejectionReasons: List<String>,
    val finalStatus: SyncStatus,
)
