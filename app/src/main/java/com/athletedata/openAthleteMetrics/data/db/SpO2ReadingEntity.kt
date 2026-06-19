package com.athletedata.openAthleteMetrics.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.data.model.MetricReading
import java.time.Instant

@Entity(
    tableName = "spo2_readings",
    indices = [Index(value = ["driver_id", "recorded_at"], unique = true)],
)
data class SpO2ReadingEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "recorded_at")
    val recordedAt: Instant,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
    val source: DataSource,
    @ColumnInfo(name = "driver_id")
    val driverId: String? = null,
    val confidence: Float? = null,
    @ColumnInfo(name = "meta_json")
    val metaJson: String? = null,
    val percentage: Double,
)

// ── Mapper ───────────────────────────────────────────────────────────────────

fun MetricReading.toSpO2Entity() = SpO2ReadingEntity(
    recordedAt = recordedAt, createdAt = createdAt,
    source = source, driverId = driverId,
    confidence = confidence, metaJson = metaJson,
    percentage = value,
)
