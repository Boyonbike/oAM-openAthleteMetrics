package com.athletedata.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.athletedata.app.data.model.DailySummary
import com.athletedata.app.data.model.DataSource
import java.time.Instant
import java.time.LocalDate

/**
 * Room entity for the `daily_summary` table.
 *
 * Pre-computed roll-up written by DailySummaryWorker after any write to
 * metric_readings or sleep_sessions. Primary key is the ISO date string
 * so the worker can upsert with [OnConflictStrategy.REPLACE] safely.
 */
@Entity(tableName = "daily_summary")
data class DailySummaryEntity(
    @PrimaryKey
    val date: LocalDate,
    @ColumnInfo(name = "avg_hr_bpm")
    val avgHrBpm: Double? = null,
    @ColumnInfo(name = "resting_hr_bpm")
    val restingHrBpm: Double? = null,
    @ColumnInfo(name = "avg_hrv_ms")
    val avgHrvMs: Double? = null,
    @ColumnInfo(name = "morning_hrv_ms")
    val morningHrvMs: Double? = null,
    @ColumnInfo(name = "avg_spo2_pct")
    val avgSpo2Pct: Double? = null,
    val steps: Int? = null,
    @ColumnInfo(name = "sleep_minutes")
    val sleepMinutes: Int? = null,
    val source: DataSource,
    @ColumnInfo(name = "computed_at")
    val computedAt: Instant,
)

// ── Mappers ──────────────────────────────────────────────────────────────────

fun DailySummaryEntity.toModel() = DailySummary(
    date = date,
    avgHrBpm = avgHrBpm,
    restingHrBpm = restingHrBpm,
    avgHrvMs = avgHrvMs,
    morningHrvMs = morningHrvMs,
    avgSpo2Pct = avgSpo2Pct,
    steps = steps,
    sleepMinutes = sleepMinutes,
    source = source,
    computedAt = computedAt,
)

fun DailySummary.toEntity() = DailySummaryEntity(
    date = date,
    avgHrBpm = avgHrBpm,
    restingHrBpm = restingHrBpm,
    avgHrvMs = avgHrvMs,
    morningHrvMs = morningHrvMs,
    avgSpo2Pct = avgSpo2Pct,
    steps = steps,
    sleepMinutes = sleepMinutes,
    source = source,
    computedAt = computedAt,
)
