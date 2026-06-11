package com.athletedata.openAthleteMetrics.ble.wasm

import com.athletedata.openAthleteMetrics.data.model.MetricType
import kotlinx.serialization.Serializable

@Serializable
internal data class MetricWasmDto(
    val metricType: MetricType,
    val value: Double,
    val unit: String,
    val recordedAtMs: Long,
    val confidence: Float? = null,
    val metaJson: String? = null,
)

@Serializable
internal data class SleepWasmDto(
    val dateIso: String,
    val sleepStartMs: Long,
    val sleepEndMs: Long,
    val durationMinutes: Int = 0,
    val stagesJson: String? = null,
)

@Serializable
internal data class ActivityWasmDto(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val durationMinutes: Int = 0,
    val deviceName: String,
    val avgHrBpm: Double? = null,
    val maxHrBpm: Double? = null,
    val minHrBpm: Double? = null,
    val calories: Double? = null,
    val activeCalories: Double? = null,
    val distanceMeters: Double? = null,
    val steps: Int? = null,
    val hrZonesJson: String? = null,
)
