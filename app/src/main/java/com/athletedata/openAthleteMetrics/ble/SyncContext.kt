package com.athletedata.openAthleteMetrics.ble

import com.athletedata.openAthleteMetrics.data.model.HrZone
import kotlinx.serialization.Serializable

@Serializable
data class SyncContext(
    val epochMs: Long,
    val utcOffsetMinutes: Int,
    val isoDateTime: String,
    val name: String?,
    val dateOfBirth: String?,
    val biologicalSex: String?,
    val heightCm: Double?,
    val weightKg: Double?,
    val strideLengthCm: Double?,
    val wristCircumferenceMm: Double?,
    val restingMetabolicRate: Double?,
    val vo2Max: Double?,
    val maxHr: Int?,
    val hrZones: List<HrZone> = emptyList(),
)
