package com.athletedata.app.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.athletedata.app.data.db.MetricReadingDao
import com.athletedata.app.data.db.SleepSessionDao
import com.athletedata.app.data.model.DailySummary
import com.athletedata.app.data.model.DataSource
import com.athletedata.app.data.model.MetricType
import com.athletedata.app.data.repository.DailySummaryRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Aggregates one day's worth of raw readings into a [DailySummary] row.
 *
 * Triggered by repository insert methods after any write to metric_readings
 * or sleep_sessions. Enqueued with REPLACE strategy so re-runs for the same
 * date are safe and idempotent.
 *
 * Uses DAOs directly (not repositories) for reads to avoid Flow overhead;
 * writes via [DailySummaryRepository.upsert] to stay on the correct abstraction
 * layer for writes.
 */
@HiltWorker
class DailySummaryWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val metricReadingDao: MetricReadingDao,
    private val sleepSessionDao: SleepSessionDao,
    private val dailySummaryRepository: DailySummaryRepository,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val dateStr = inputData.getString(KEY_DATE) ?: return Result.failure()

        return try {
            val date = LocalDate.parse(dateStr)

            // ── Time boundaries ───────────────────────────────────────────────
            val dayStartMs = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val dayEndMs   = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            // Overnight window for resting HR (00:00–06:00 UTC)
            val nightEndMs     = date.atTime(6, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
            // Morning window for first HRV reading (after 05:00 UTC)
            val morningStartMs = date.atTime(5, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

            // ── Fetch raw readings ────────────────────────────────────────────
            val hrReadings    = metricReadingDao.getReadingsInRangeOnce(MetricType.HR,    dayStartMs, dayEndMs)
            val hrvReadings   = metricReadingDao.getReadingsInRangeOnce(MetricType.HRV,   dayStartMs, dayEndMs)
            val spo2Readings  = metricReadingDao.getReadingsInRangeOnce(MetricType.SPO2,  dayStartMs, dayEndMs)
            val stepsReadings = metricReadingDao.getReadingsInRangeOnce(MetricType.STEPS, dayStartMs, dayEndMs)
            val sleepSession  = sleepSessionDao.getSessionForDateOnce(date)

            // ── Compute aggregates ────────────────────────────────────────────

            // Mean HR across all readings for the day
            val avgHrBpm = hrReadings.averageOrNull { it.value }

            // Resting HR: minimum value in the overnight window (00:00–06:00 UTC).
            // Approximates the "lowest 5-minute HR window" for seeder-density data
            // (one reading per 5 min); a sliding-window average can replace this
            // when real device data provides higher-frequency readings.
            val restingHrBpm = hrReadings
                .filter { it.recordedAt.toEpochMilli() < nightEndMs }
                .minByOrNull { it.value }
                ?.value

            // Mean HRV across all readings for the day
            val avgHrvMs = hrvReadings.averageOrNull { it.value }

            // First HRV reading at or after 05:00 UTC (proxy for wake-time HRV)
            val morningHrvMs = hrvReadings
                .filter { it.recordedAt.toEpochMilli() >= morningStartMs }
                .minByOrNull { it.recordedAt }
                ?.value

            // Mean SpO₂ across overnight readings
            val avgSpo2Pct = spo2Readings.averageOrNull { it.value }

            // Steps: the seeder writes one cumulative total per day; take the last value
            val steps = stepsReadings.maxByOrNull { it.recordedAt }?.value?.toInt()

            // Sleep duration from the sleep session for this night
            val sleepMinutes = sleepSession?.durationMinutes

            // ── Determine dominant source ─────────────────────────────────────
            // Most common source among all metric readings; fall back to the sleep
            // session's source, then MANUAL if there are no readings at all.
            val allReadings = hrReadings + hrvReadings + spo2Readings + stepsReadings
            val dominantSource = allReadings
                .groupingBy { it.source }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key
                ?: sleepSession?.source
                ?: DataSource.MANUAL

            // ── Write summary ─────────────────────────────────────────────────
            dailySummaryRepository.upsert(
                DailySummary(
                    date = date,
                    avgHrBpm = avgHrBpm,
                    restingHrBpm = restingHrBpm,
                    avgHrvMs = avgHrvMs,
                    morningHrvMs = morningHrvMs,
                    avgSpo2Pct = avgSpo2Pct,
                    steps = steps,
                    sleepMinutes = sleepMinutes,
                    source = dominantSource,
                    computedAt = Instant.now(),
                )
            )

            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }

    companion object {
        /** Input data key for the ISO date string (YYYY-MM-DD). */
        const val KEY_DATE = "date"
    }
}

/**
 * Enqueues a [DailySummaryWorker] for [date] with REPLACE conflict strategy.
 *
 * REPLACE means if a job for the same date is already queued or running it
 * is cancelled and replaced — safe because the worker is fully idempotent.
 *
 * Called at the end of every repository method that writes to metric_readings
 * or sleep_sessions. The [workManager] parameter is injected by Hilt into the
 * calling repository rather than obtained from context here, so there is no
 * risk of double-initialisation before [AthleteDataApplication] sets up the
 * custom [Configuration.Provider].
 */
fun enqueueSummaryWorker(date: LocalDate, workManager: WorkManager) {
    val request = OneTimeWorkRequestBuilder<DailySummaryWorker>()
        .setInputData(workDataOf(DailySummaryWorker.KEY_DATE to date.toString()))
        .build()
    workManager.enqueueUniqueWork(
        "daily_summary_$date",
        ExistingWorkPolicy.REPLACE,
        request,
    )
}

// ── Internal helpers ──────────────────────────────────────────────────────────

/** Returns the average of [selector] over the list, or null if the list is empty. */
private fun <T> List<T>.averageOrNull(selector: (T) -> Double): Double? =
    takeIf { isNotEmpty() }?.map(selector)?.average()
