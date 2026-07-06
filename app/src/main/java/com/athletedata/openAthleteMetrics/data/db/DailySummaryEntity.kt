package com.athletedata.openAthleteMetrics.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.athletedata.openAthleteMetrics.data.model.DailySummary
import com.athletedata.openAthleteMetrics.data.model.DataSource
import java.time.Instant
import java.time.LocalDate

/**
 * Room entity for the `daily_summary` table.
 *
 * Pre-computed roll-up written by DailySummaryWorker after any write to
 * the dedicated time-series tables or sleep_sessions. Primary key is the
 * ISO date string so the worker can upsert with [OnConflictStrategy.REPLACE] safely.
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
    @ColumnInfo(name = "overnight_hrv_ms")
    val overnightHrvMs: Double? = null,
    @ColumnInfo(name = "avg_spo2_pct")
    val avgSpo2Pct: Double? = null,
    val steps: Int? = null,
    @ColumnInfo(name = "sleep_minutes")
    val sleepMinutes: Int? = null,
    @ColumnInfo(name = "sleep_deep_minutes")
    val sleepDeepMinutes: Int? = null,
    @ColumnInfo(name = "sleep_light_minutes")
    val sleepLightMinutes: Int? = null,
    @ColumnInfo(name = "sleep_rem_minutes")
    val sleepRemMinutes: Int? = null,
    @ColumnInfo(name = "sleep_awake_minutes")
    val sleepAwakeMinutes: Int? = null,
    @ColumnInfo(name = "skin_temp_avg_c")
    val skinTempAvgC: Double? = null,
    @ColumnInfo(name = "skin_temp_min_c")
    val skinTempMinC: Double? = null,
    @ColumnInfo(name = "skin_temp_max_c")
    val skinTempMaxC: Double? = null,
    @ColumnInfo(name = "respiration_avg")
    val respirationAvg: Double? = null,
    @ColumnInfo(name = "hrv_min_ms")
    val hrvMinMs: Double? = null,
    @ColumnInfo(name = "hrv_max_ms")
    val hrvMaxMs: Double? = null,
    @ColumnInfo(name = "spo2_min_pct")
    val spo2MinPct: Double? = null,
    @ColumnInfo(name = "spo2_max_pct")
    val spo2MaxPct: Double? = null,
    @ColumnInfo(name = "steps_active_minutes")
    val stepsActiveMinutes: Int? = null,
    @ColumnInfo(name = "total_calories")
    val totalCalories: Double? = null,
    @ColumnInfo(name = "active_calories")
    val activeCalories: Double? = null,
    @ColumnInfo(name = "avg_systolic_mm_hg") // BP-GLUCOSE-SUMMARY
    val avgSystolicMmHg: Double? = null, // BP-GLUCOSE-SUMMARY
    @ColumnInfo(name = "avg_diastolic_mm_hg") // BP-GLUCOSE-SUMMARY
    val avgDiastolicMmHg: Double? = null, // BP-GLUCOSE-SUMMARY
    @ColumnInfo(name = "avg_glucose_mmol_l") // BP-GLUCOSE-SUMMARY
    val avgGlucoseMmolL: Double? = null, // BP-GLUCOSE-SUMMARY
    /** Applies to any computed/derived artifact table, not only raw reading tables — the daily summary is itself a computed roll-up. */
    @ColumnInfo(name = "computed_by_version", defaultValue = "0")
    val computedByVersion: Int = 0,
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
    overnightHrvMs = overnightHrvMs,
    avgSpo2Pct = avgSpo2Pct,
    steps = steps,
    sleepMinutes = sleepMinutes,
    sleepDeepMinutes = sleepDeepMinutes,
    sleepLightMinutes = sleepLightMinutes,
    sleepRemMinutes = sleepRemMinutes,
    sleepAwakeMinutes = sleepAwakeMinutes,
    skinTempAvgC = skinTempAvgC,
    skinTempMinC = skinTempMinC,
    skinTempMaxC = skinTempMaxC,
    respirationAvg = respirationAvg,
    hrvMinMs = hrvMinMs,
    hrvMaxMs = hrvMaxMs,
    spo2MinPct = spo2MinPct,
    spo2MaxPct = spo2MaxPct,
    stepsActiveMinutes = stepsActiveMinutes,
    totalCalories = totalCalories,
    activeCalories = activeCalories,
    avgSystolicMmHg = avgSystolicMmHg, // BP-GLUCOSE-SUMMARY
    avgDiastolicMmHg = avgDiastolicMmHg, // BP-GLUCOSE-SUMMARY
    avgGlucoseMmolL = avgGlucoseMmolL, // BP-GLUCOSE-SUMMARY
    computedByVersion = computedByVersion,
    source = source,
    computedAt = computedAt,
)

fun DailySummary.toEntity() = DailySummaryEntity(
    date = date,
    avgHrBpm = avgHrBpm,
    restingHrBpm = restingHrBpm,
    avgHrvMs = avgHrvMs,
    overnightHrvMs = overnightHrvMs,
    avgSpo2Pct = avgSpo2Pct,
    steps = steps,
    sleepMinutes = sleepMinutes,
    sleepDeepMinutes = sleepDeepMinutes,
    sleepLightMinutes = sleepLightMinutes,
    sleepRemMinutes = sleepRemMinutes,
    sleepAwakeMinutes = sleepAwakeMinutes,
    skinTempAvgC = skinTempAvgC,
    skinTempMinC = skinTempMinC,
    skinTempMaxC = skinTempMaxC,
    respirationAvg = respirationAvg,
    hrvMinMs = hrvMinMs,
    hrvMaxMs = hrvMaxMs,
    spo2MinPct = spo2MinPct,
    spo2MaxPct = spo2MaxPct,
    stepsActiveMinutes = stepsActiveMinutes,
    totalCalories = totalCalories,
    activeCalories = activeCalories,
    avgSystolicMmHg = avgSystolicMmHg, // BP-GLUCOSE-SUMMARY
    avgDiastolicMmHg = avgDiastolicMmHg, // BP-GLUCOSE-SUMMARY
    avgGlucoseMmolL = avgGlucoseMmolL, // BP-GLUCOSE-SUMMARY
    computedByVersion = computedByVersion,
    source = source,
    computedAt = computedAt,
)
