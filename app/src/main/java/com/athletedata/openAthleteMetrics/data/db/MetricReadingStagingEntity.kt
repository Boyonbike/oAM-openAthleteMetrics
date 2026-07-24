package com.athletedata.openAthleteMetrics.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.data.model.MetricReading
import com.athletedata.openAthleteMetrics.data.model.MetricType
import java.time.Instant

/**
 * Room entity for the `metric_readings_staging` table.
 *
 * Landing zone for unknown metric types reported by JSON manifest drivers
 * that do not have a dedicated typed table yet. All previously-flat
 * metric_readings rows land here until they are promoted to their own
 * dedicated table in a future migration.
 */
@Entity(
    tableName = "metric_readings_staging",
    indices = [
        Index(value = ["metric_type", "recorded_at"], orders = [Index.Order.ASC, Index.Order.DESC]),
        Index(value = ["driver_id", "metric_type", "recorded_at"], unique = true),
    ],
)
data class MetricReadingStagingEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "metric_type")
    val metricType: MetricType,
    val value: Double,
    val unit: String,
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
    // Physical device (numeric devices.id), not the driver.
    @ColumnInfo(name = "device_id")
    val deviceId: Long? = null,
)

// ── Mappers ──────────────────────────────────────────────────────────────────

fun MetricReadingStagingEntity.toModel() = MetricReading(
    id = id,
    metricType = metricType,
    value = value,
    unit = unit,
    recordedAt = recordedAt,
    createdAt = createdAt,
    source = source,
    driverId = driverId,
    confidence = confidence,
    metaJson = metaJson,
    deviceId = deviceId,
)

fun MetricReading.toStagingEntity() = MetricReadingStagingEntity(
    id = id,
    metricType = metricType,
    value = value,
    unit = unit,
    recordedAt = recordedAt,
    createdAt = createdAt,
    source = source,
    driverId = driverId,
    confidence = confidence,
    metaJson = metaJson,
    deviceId = deviceId,
)
