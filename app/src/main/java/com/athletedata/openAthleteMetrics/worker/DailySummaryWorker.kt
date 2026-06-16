package com.athletedata.openAthleteMetrics.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.athletedata.openAthleteMetrics.data.db.ActiveCalorieReadingDao
import com.athletedata.openAthleteMetrics.data.db.HrReadingDao
import com.athletedata.openAthleteMetrics.data.db.HrvReadingDao
import com.athletedata.openAthleteMetrics.data.db.RespirationReadingDao
import com.athletedata.openAthleteMetrics.data.db.SkinTempReadingDao
import com.athletedata.openAthleteMetrics.data.db.SleepSessionDao
import com.athletedata.openAthleteMetrics.data.db.SleepStageDao
import com.athletedata.openAthleteMetrics.data.db.SpO2ReadingDao
import com.athletedata.openAthleteMetrics.data.db.StepsReadingDao
import com.athletedata.openAthleteMetrics.data.db.TotalCalorieReadingDao
import com.athletedata.openAthleteMetrics.data.model.DailySummary
import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.data.model.SleepStage
import com.athletedata.openAthleteMetrics.data.repository.DailySummaryRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Aggregates one day's worth of raw readings into a [DailySummary] row.
 *
 * Triggered after any write to the typed time-series tables or sleep_sessions.
 * Enqueued with REPLACE strategy so re-runs for the same date are idempotent.
 *
 * Uses DAOs directly for reads to avoid Flow overhead; writes via
 * [DailySummaryRepository.upsert] to stay on the correct abstraction layer.
 */
@HiltWorker
class DailySummaryWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val hrReadingDao: HrReadingDao,
    private val hrvReadingDao: HrvReadingDao,
    private val spo2ReadingDao: SpO2ReadingDao,
    private val skinTempReadingDao: SkinTempReadingDao,
    private val respirationReadingDao: RespirationReadingDao,
    private val stepsReadingDao: StepsReadingDao,
    private val activeCalorieReadingDao: ActiveCalorieReadingDao,
    private val totalCalorieReadingDao: TotalCalorieReadingDao,
    private val sleepSessionDao: SleepSessionDao,
    private val sleepStageDao: SleepStageDao,
    private val dailySummaryRepository: DailySummaryRepository,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val dateStr = inputData.getString(KEY_DATE) ?: return Result.failure()

        return try {
            val date = LocalDate.parse(dateStr)

            // ── Time boundaries ───────────────────────────────────────────────
            val dayStartMs     = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val dayEndMs       = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val nightEndMs     = date.atTime(6, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
            val morningStartMs = date.atTime(5, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

            // ── Fetch raw readings ────────────────────────────────────────────
            val hrReadings          = hrReadingDao.getReadingsInRangeOnce(dayStartMs, dayEndMs)
            val hrvReadings         = hrvReadingDao.getReadingsInRangeOnce(dayStartMs, dayEndMs)
            val spo2Readings        = spo2ReadingDao.getReadingsInRangeOnce(dayStartMs, dayEndMs)
            val skinTempReadings    = skinTempReadingDao.getReadingsInRangeOnce(dayStartMs, dayEndMs)
            val respirationReadings = respirationReadingDao.getReadingsInRangeOnce(dayStartMs, dayEndMs)
            val stepsReadings       = stepsReadingDao.getReadingsInRangeOnce(dayStartMs, dayEndMs)
            val activeCalReadings   = activeCalorieReadingDao.getReadingsInRangeOnce(dayStartMs, dayEndMs)
            val totalCalReadings    = totalCalorieReadingDao.getReadingsInRangeOnce(dayStartMs, dayEndMs)
            val session             = sleepSessionDao.getSessionForDateOnce(date)
            val stages              = if (session != null) sleepStageDao.getStagesForSessionOnce(session.id) else emptyList()

            // ── HR ────────────────────────────────────────────────────────────
            val avgHrBpm     = hrReadings.averageOrNull { it.bpm.toDouble() }
            val restingHrBpm = hrReadings
                .filter { it.recordedAt.toEpochMilli() in dayStartMs until nightEndMs }
                .groupBy { it.recordedAt.toEpochMilli() / 300_000L }
                .values
                .filter { it.size >= 2 }
                .map { bucket -> bucket.map { it.bpm }.average() }
                .minOrNull()

            // ── HRV ───────────────────────────────────────────────────────────
            val avgHrvMs     = hrvReadings.averageOrNull { it.rmssdMs }
            val morningHrvMs = hrvReadings
                .filter { it.recordedAt.toEpochMilli() >= morningStartMs }
                .minByOrNull { it.recordedAt }
                ?.rmssdMs
            val hrvMinMs     = hrvReadings.minByOrNull { it.rmssdMs }?.rmssdMs
            val hrvMaxMs     = hrvReadings.maxByOrNull { it.rmssdMs }?.rmssdMs

            // ── SpO2 ──────────────────────────────────────────────────────────
            val avgSpo2Pct  = spo2Readings.averageOrNull { it.percentage }
            val spo2MinPct  = spo2Readings.minByOrNull { it.percentage }?.percentage
            val spo2MaxPct  = spo2Readings.maxByOrNull { it.percentage }?.percentage

            // ── Skin temperature ──────────────────────────────────────────────
            val skinTempAvgC = skinTempReadings.averageOrNull { it.celsius }
            val skinTempMinC = skinTempReadings.minByOrNull { it.celsius }?.celsius
            val skinTempMaxC = skinTempReadings.maxByOrNull { it.celsius }?.celsius

            // ── Respiration ───────────────────────────────────────────────────
            val respirationAvg = respirationReadings.averageOrNull { it.breathsPerMinute }

            // ── Steps ─────────────────────────────────────────────────────────
            val steps = stepsReadings.maxByOrNull { it.recordedAt }?.cumulativeSteps
            val stepsActiveMinutes = stepsReadings
                .takeIf { it.size >= 2 }
                ?.zipWithNext { a, b ->
                    val stepDelta = b.cumulativeSteps - a.cumulativeSteps
                    val intervalMinutes = (b.recordedAt.toEpochMilli() - a.recordedAt.toEpochMilli()) / 60_000L
                    if (stepDelta > 500 && intervalMinutes > 0) intervalMinutes.coerceAtMost(60L) else 0L
                }
                ?.sum()
                ?.toInt()

            // ── Calories ──────────────────────────────────────────────────────
            val activeCalories = activeCalReadings
                .sumOf { it.calories }
                .takeIf { activeCalReadings.isNotEmpty() }
            val totalCalories = totalCalReadings.maxByOrNull { it.recordedAt }?.calories

            // ── Sleep ─────────────────────────────────────────────────────────
            val sleepMinutes      = session?.durationMinutes
            val sleepDeepMinutes  = stages.filter { it.stage == SleepStage.DEEP  }.sumOf { it.durationMinutes }.takeIf { stages.isNotEmpty() }
            val sleepLightMinutes = stages.filter { it.stage == SleepStage.LIGHT }.sumOf { it.durationMinutes }.takeIf { stages.isNotEmpty() }
            val sleepRemMinutes   = stages.filter { it.stage == SleepStage.REM   }.sumOf { it.durationMinutes }.takeIf { stages.isNotEmpty() }
            val sleepAwakeMinutes = stages.filter { it.stage == SleepStage.AWAKE }.sumOf { it.durationMinutes }.takeIf { stages.isNotEmpty() }

            // ── Dominant source ───────────────────────────────────────────────
            val allSources = hrReadings.map { it.source } +
                hrvReadings.map { it.source } +
                spo2Readings.map { it.source } +
                skinTempReadings.map { it.source } +
                respirationReadings.map { it.source } +
                stepsReadings.map { it.source } +
                activeCalReadings.map { it.source } +
                totalCalReadings.map { it.source }
            val dominantSource = allSources
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key
                ?: session?.source
                ?: DataSource.MANUAL

            // ── Write summary ─────────────────────────────────────────────────
            dailySummaryRepository.upsert(
                DailySummary(
                    date               = date,
                    avgHrBpm           = avgHrBpm,
                    restingHrBpm       = restingHrBpm,
                    avgHrvMs           = avgHrvMs,
                    morningHrvMs       = morningHrvMs,
                    hrvMinMs           = hrvMinMs,
                    hrvMaxMs           = hrvMaxMs,
                    avgSpo2Pct         = avgSpo2Pct,
                    spo2MinPct         = spo2MinPct,
                    spo2MaxPct         = spo2MaxPct,
                    skinTempAvgC       = skinTempAvgC,
                    skinTempMinC       = skinTempMinC,
                    skinTempMaxC       = skinTempMaxC,
                    respirationAvg     = respirationAvg,
                    steps              = steps,
                    stepsActiveMinutes = stepsActiveMinutes,
                    activeCalories     = activeCalories,
                    totalCalories      = totalCalories,
                    sleepMinutes       = sleepMinutes,
                    sleepDeepMinutes   = sleepDeepMinutes,
                    sleepLightMinutes  = sleepLightMinutes,
                    sleepRemMinutes    = sleepRemMinutes,
                    sleepAwakeMinutes  = sleepAwakeMinutes,
                    computedByVersion  = 1,
                    source             = dominantSource,
                    computedAt         = Instant.now(),
                )
            )

            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "DailySummaryWorker failed for date $dateStr")
            Result.retry()
        }
    }

    companion object {
        const val KEY_DATE = "date"
    }
}

/**
 * Enqueues a [DailySummaryWorker] for [date] with REPLACE conflict strategy.
 *
 * REPLACE means if a job for the same date is already queued or running it
 * is cancelled and replaced — safe because the worker is fully idempotent.
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

private fun <T> List<T>.averageOrNull(selector: (T) -> Double): Double? =
    takeIf { isNotEmpty() }?.map(selector)?.average()
