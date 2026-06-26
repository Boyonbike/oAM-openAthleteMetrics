package com.athletedata.openAthleteMetrics.data.model

import kotlinx.serialization.Serializable

/** A single heart-rate training zone, stored as part of [UserProfile]. */
@Serializable
data class HrZone(
    /** Zone number 1–5. */
    val zone: Int,
    /** Lower bound of this zone in bpm (inclusive). */
    val lowerBpm: Int,
    /** Upper bound of this zone in bpm (inclusive). */
    val upperBpm: Int,
)
