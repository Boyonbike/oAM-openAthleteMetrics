package com.athletedata.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.athletedata.app.data.model.DataSource
import com.athletedata.app.data.model.MetricReading
import com.athletedata.app.data.model.MetricType
import java.time.Instant

/**
 * Room entity for the `metric_readings` table.
 *
 * Every sensor value from every source — device, manual, or seeder — lands
 * here. The composite index on (metric_type, recorded_at) covers the most
 * common access pattern: "all HR readings for a time range."
 */
@Entity(
    tableName = "metric_readings",
    indices = [Index(value = ["metric_type", "recorded_at"])],
)
data class MetricReadingEntity(
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
)

// ── Mappers ──────────────────────────────────────────────────────────────────

fun MetricReadingEntity.toModel() = MetricReading(
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
)

fun MetricReading.toEntity() = MetricReadingEntity(
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
)
