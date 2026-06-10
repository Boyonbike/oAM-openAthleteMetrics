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
 * One row per sleep session (typically one per night). The [stagesJson]
 * column holds a JSON array of stage blocks rather than individual rows,
 * keeping dashboard queries simple.
 */
@Entity(
    tableName = "sleep_sessions",
    indices = [Index(value = ["driver_id", "date"], unique = true)],
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
    @ColumnInfo(name = "stages_json")
    val stagesJson: String? = null,
    val source: DataSource,
    @ColumnInfo(name = "driver_id")
    val driverId: String? = null,
)

// ── Mappers ──────────────────────────────────────────────────────────────────

fun SleepSessionEntity.toModel() = SleepSession(
    id = id,
    date = date,
    sleepStartMs = sleepStartMs,
    sleepEndMs = sleepEndMs,
    durationMinutes = durationMinutes,
    stagesJson = stagesJson,
    source = source,
    driverId = driverId,
)

fun SleepSession.toEntity() = SleepSessionEntity(
    id = id,
    date = date,
    sleepStartMs = sleepStartMs,
    sleepEndMs = sleepEndMs,
    durationMinutes = durationMinutes,
    stagesJson = stagesJson,
    source = source,
    driverId = driverId,
)
