package com.athletedata.openAthleteMetrics.data.model

/**
 * Single-row athlete profile. [weightKg] is managed by the daily weight-log sync;
 * do not expose it as an editable field in the profile UI.
 */
data class UserProfile(
    /** Always 1 — enforces the single-row constraint. */
    val id: Int = 1,
    /** Athlete's display name. */
    val name: String? = null,
    /** Date of birth in ISO format YYYY-MM-DD. */
    val dateOfBirth: String? = null,
    /** Biological sex — one of MALE, FEMALE, OTHER. */
    val biologicalSex: String? = null,
    /** Standing height in centimetres. */
    val heightCm: Double? = null,
    /** Body weight in kilograms; auto-synced from the daily weight log. */
    val weightKg: Double? = null,
    /** Average walking/running stride length in centimetres. */
    val strideLengthCm: Double? = null,
    /** Wrist circumference in millimetres, used for wearable-fit metrics. */
    val wristCircumferenceMm: Double? = null,
    /** Resting metabolic rate in kcal/day. */
    val restingMetabolicRate: Double? = null,
    /** VO₂ max in ml/kg/min. */
    val vo2Max: Double? = null,
    /** Measured maximum heart rate in bpm. */
    val maxHr: Int? = null,
    /** Heart-rate training zones derived from [maxHr]. */
    val hrZones: List<HrZone> = emptyList(),
    /** Epoch millisecond timestamp of the last write. */
    val updatedAt: Long = System.currentTimeMillis(),
)
