package com.athletedata.openAthleteMetrics.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.data.model.MetricReading
import java.time.Instant
import org.json.JSONObject

@Entity(
    tableName = "blood_pressure_readings",
    indices = [Index(value = ["driver_id", "recorded_at"], unique = true)],
)
data class BloodPressureReadingEntity(
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
    val systolic: Int,
    val diastolic: Int,
)

// ── Mapper ───────────────────────────────────────────────────────────────────

fun MetricReading.toBloodPressureEntityOrNull(): BloodPressureReadingEntity? {
    val diastolic = runCatching {
        JSONObject(metaJson ?: "").getInt("diastolic")
    }.getOrNull() ?: return null
    return BloodPressureReadingEntity(
        recordedAt = recordedAt, createdAt = createdAt,
        source = source, driverId = driverId,
        confidence = confidence, metaJson = metaJson,
        systolic = value.toInt(),
        diastolic = diastolic,
    )
}
