package com.athletedata.openAthleteMetrics.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.athletedata.openAthleteMetrics.data.model.Activity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.data.model.UserCategory
import java.time.Instant

@Entity(
    tableName = "activities",
    indices = [Index(value = ["device_id", "start_time"], unique = true)],
)
data class ActivityEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "start_time")
    val startTime: Instant,
    @ColumnInfo(name = "end_time")
    val endTime: Instant,
    @ColumnInfo(name = "duration_minutes")
    val durationMinutes: Int,
    @ColumnInfo(name = "device_name")
    val deviceName: String,
    @ColumnInfo(name = "user_category")
    val userCategory: UserCategory? = null,
    val notes: String? = null,
    val source: DataSource,
    @ColumnInfo(name = "driver_id")
    val driverId: String? = null,
    @ColumnInfo(name = "avg_hr_bpm")
    val avgHrBpm: Double? = null,
    @ColumnInfo(name = "max_hr_bpm")
    val maxHrBpm: Double? = null,
    @ColumnInfo(name = "min_hr_bpm")
    val minHrBpm: Double? = null,
    val calories: Double? = null,
    @ColumnInfo(name = "active_calories")
    val activeCalories: Double? = null,
    @ColumnInfo(name = "distance_meters")
    val distanceMeters: Double? = null,
    val steps: Int? = null,
    @ColumnInfo(name = "hr_zones_json")
    val hrZonesJson: String? = null,
    // Physical device (numeric devices.id), not the driver.
    @ColumnInfo(name = "device_id")
    val deviceId: Long? = null,
)

// ── Mappers ──────────────────────────────────────────────────────────────────

fun ActivityEntity.toModel() = Activity(
    id = id,
    startTime = startTime,
    endTime = endTime,
    durationMinutes = durationMinutes,
    deviceName = deviceName,
    userCategory = userCategory,
    notes = notes,
    source = source,
    driverId = driverId,
    avgHrBpm = avgHrBpm,
    maxHrBpm = maxHrBpm,
    minHrBpm = minHrBpm,
    calories = calories,
    activeCalories = activeCalories,
    distanceMeters = distanceMeters,
    steps = steps,
    hrZonesJson = hrZonesJson,
    deviceId = deviceId,
)

fun Activity.toEntity() = ActivityEntity(
    id = id,
    startTime = startTime,
    endTime = endTime,
    durationMinutes = durationMinutes,
    deviceName = deviceName,
    userCategory = userCategory,
    notes = notes,
    source = source,
    driverId = driverId,
    avgHrBpm = avgHrBpm,
    maxHrBpm = maxHrBpm,
    minHrBpm = minHrBpm,
    calories = calories,
    activeCalories = activeCalories,
    distanceMeters = distanceMeters,
    steps = steps,
    hrZonesJson = hrZonesJson,
    deviceId = deviceId,
)
