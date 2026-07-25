package com.athletedata.openAthleteMetrics.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.data.model.SleepSession
import java.time.Instant
import java.time.LocalDate

/**
 * Room entity for the `sleep_sessions` table.
 *
 * One row per sleep session (typically one per night). Stage data is now
 * stored in the dedicated `sleep_stages` table rather than as a JSON blob.
 */
@Entity(
    tableName = "sleep_sessions",
    indices = [Index(value = ["device_id", "date"], unique = true)],
)
data class SleepSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val date: LocalDate,
    @ColumnInfo(name = "sleep_start_ms")
    val sleepStartMs: Instant,
    @ColumnInfo(name = "sleep_end_ms")
    val sleepEndMs: Instant,
    @ColumnInfo(name = "duration_minutes")
    val durationMinutes: Int,
    val source: DataSource,
    @ColumnInfo(name = "driver_id")
    val driverId: String? = null,
    // Physical device (numeric devices.id), not the driver.
    @ColumnInfo(name = "device_id")
    val deviceId: Long? = null,
)

// ── Mappers ──────────────────────────────────────────────────────────────────

fun SleepSessionEntity.toModel() = SleepSession(
    id = id,
    date = date,
    sleepStartMs = sleepStartMs,
    sleepEndMs = sleepEndMs,
    durationMinutes = durationMinutes,
    source = source,
    driverId = driverId,
    deviceId = deviceId,
)

fun SleepSession.toEntity() = SleepSessionEntity(
    id = id,
    date = date,
    sleepStartMs = sleepStartMs,
    sleepEndMs = sleepEndMs,
    durationMinutes = durationMinutes,
    source = source,
    driverId = driverId,
    deviceId = deviceId,
)
