package com.athletedata.openAthleteMetrics.data.model

import java.time.Instant
import java.time.LocalDate

/**
 * Pre-computed daily roll-up written by [DailySummaryWorker] after any write
 * to [MetricReading] or [SleepSession].
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
 * @property sleepMinutes Total sleep duration from the night's [SleepSession].
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
    val source: DataSource,
    val computedAt: Instant,
)
