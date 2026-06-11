package com.athletedata.openAthleteMetrics.data.model

import java.time.Instant

data class SyncSession(
    val id: Long = 0,
    val deviceId: Long,
    val driverId: String,
    val startedAt: Instant,
    val endedAt: Instant? = null,
    val status: SyncStatus,
    val recordsImported: Int,
    val errorMessage: String? = null,
    val packetsReceived: Int = 0,
    val syncedBeforeQuiescence: Boolean = false,
)
