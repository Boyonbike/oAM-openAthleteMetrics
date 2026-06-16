package com.athletedata.openAthleteMetrics.data.model

import java.time.Instant
import java.time.LocalDate

/**
 * Pre-computed daily roll-up written by [DailySummaryWorker] after any write
 * to the dedicated time-series tables or sleep_sessions.
 *
 * The Daily Overview reads this table rather than aggregating raw readings on
 * every render, keeping dashboard loads fast regardless of reading volume.
 * All metric fields are nullable because not every day has every metric.
 *
 * @property date The calendar day this summary covers (ISO YYYY-MM-DD).
 * @property avgHrBpm Mean heart rate for the day.
 * @property restingHrBpm Lowest 5-minute HR window found during the day.
 * @property avgHrvMs Mean HRV across all readings for the day.
 * @property morningHrvMs First HRV reading after wake time.
 * @property avgSpo2Pct Mean blood-oxygen saturation across overnight readings.
 * @property steps Total step count for the day.
 * @property sleepMinutes Total sleep duration from the night's session.
 * @property sleepDeepMinutes Minutes in deep sleep stage.
 * @property sleepLightMinutes Minutes in light sleep stage.
 * @property sleepRemMinutes Minutes in REM sleep stage.
 * @property sleepAwakeMinutes Minutes marked as awake during the sleep session.
 * @property skinTempAvgC Average skin temperature (°C).
 * @property skinTempMinC Minimum skin temperature reading (°C).
 * @property skinTempMaxC Maximum skin temperature reading (°C).
 * @property respirationAvg Average respiration rate (breaths/min).
 * @property hrvMinMs Minimum HRV reading (ms).
 * @property hrvMaxMs Maximum HRV reading (ms).
 * @property spo2MinPct Minimum SpO2 percentage.
 * @property spo2MaxPct Maximum SpO2 percentage.
 * @property stepsActiveMinutes Minutes with elevated step activity.
 * @property totalCalories Full-day calorie expenditure including resting metabolic rate.
 * @property activeCalories Calories burned during activity periods only.
 * @property computedByVersion Algorithm version that produced this row.
 * @property source Source of the underlying readings that produced this summary.
 * @property computedAt When this row was last written by the worker.
 */
data class DailySummary(
    val date: LocalDate,
    val avgHrBpm: Double? = null,
    val restingHrBpm: Double? = null,
    val avgHrvMs: Double? = null,
    val morningHrvMs: Double? = null,
    val avgSpo2Pct: Double? = null,
    val steps: Int? = null,
    val sleepMinutes: Int? = null,
    val sleepDeepMinutes: Int? = null,
    val sleepLightMinutes: Int? = null,
    val sleepRemMinutes: Int? = null,
    val sleepAwakeMinutes: Int? = null,
    val skinTempAvgC: Double? = null,
    val skinTempMinC: Double? = null,
    val skinTempMaxC: Double? = null,
    val respirationAvg: Double? = null,
    val hrvMinMs: Double? = null,
    val hrvMaxMs: Double? = null,
    val spo2MinPct: Double? = null,
    val spo2MaxPct: Double? = null,
    val stepsActiveMinutes: Int? = null,
    val totalCalories: Double? = null,
    val activeCalories: Double? = null,
    val computedByVersion: Int = 0,
    val source: DataSource,
    val computedAt: Instant,
)
