package com.athletedata.openAthleteMetrics.data.model

data class Device(
    val id: Long = 0,
    val bleAddress: String,
    val driverId: String,
    val displayName: String,
    val lastSeenMs: Long? = null,
    val lastSyncMs: Long? = null,
    val lastBatteryPct: Int? = null,
    val isPrimary: Boolean = false,
    val autoSyncEnabled: Boolean = true,
    val cdmAssociated: Boolean = false,
)
