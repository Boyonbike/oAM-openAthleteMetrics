package com.athletedata.openAthleteMetrics.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.athletedata.openAthleteMetrics.ble.driver.StepsMode // STEPS-MODE
import com.athletedata.openAthleteMetrics.data.db.ActiveCalorieReadingDao
import com.athletedata.openAthleteMetrics.data.db.BloodPressureReadingDao // BP-GLUCOSE-SUMMARY
import com.athletedata.openAthleteMetrics.data.db.GlucoseReadingDao // BP-GLUCOSE-SUMMARY
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
import com.athletedata.openAthleteMetrics.data.repository.MetricDailyStatsWriter
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

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
    private val bloodPressureReadingDao: BloodPressureReadingDao, // BP-GLUCOSE-SUMMARY
    private val glucoseReadingDao: GlucoseReadingDao, // BP-GLUCOSE-SUMMARY
    private val sleepSessionDao: SleepSessionDao,
    private val sleepStageDao: SleepStageDao,
    private val dailySummaryRepository: DailySummaryRepository,
    private val overnightHrvCalculator: OvernightHrvCalculator,
    private val metricDailyStatsWriter: MetricDailyStatsWriter,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val dateStr = inputData.getString(KEY_DATE) ?: return Result.failure()
        // STEPS-MODE: read from WorkManager input; absent → DELTA (preserves existing behaviour).
        val stepsMode = StepsMode.valueOf(inputData.getString(KEY_STEPS_MODE) ?: StepsMode.DELTA.name) // STEPS-MODE

        val date = try {
            LocalDate.parse(dateStr)
        } catch (e: DateTimeParseException) {
            Timber.tag(TAG).e(e, "[STAGE-7 SUMMARY-WORKER] malformed date=%s — failing permanently", dateStr) // DPT
            return Result.failure()
        }

        return try {
            Timber.tag(TAG).d("[STAGE-7 SUMMARY-WORKER] started for date=%s", dateStr) // DPT

            // ── Time boundaries ───────────────────────────────────────────────
            val zone           = ZoneId.systemDefault()
            val dayStartMs     = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val dayEndMs       = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val nightEndMs     = date.atTime(6, 0).atZone(zone).toInstant().toEpochMilli()

            // ── Fetch raw readings ────────────────────────────────────────────
            val hrReadings          = hrReadingDao.getReadingsInRangeOnce(dayStartMs, dayEndMs)
            val hrvReadings         = hrvReadingDao.getReadingsInRangeOnce(dayStartMs, dayEndMs)
            val spo2Readings        = spo2ReadingDao.getReadingsInRangeOnce(dayStartMs, dayEndMs)
            val skinTempReadings    = skinTempReadingDao.getReadingsInRangeOnce(dayStartMs, dayEndMs)
            val respirationReadings = respirationReadingDao.getReadingsInRangeOnce(dayStartMs, dayEndMs)
            val stepsReadings       = stepsReadingDao.getReadingsInRangeOnce(dayStartMs, dayEndMs)
            val activeCalReadings   = activeCalorieReadingDao.getReadingsInRangeOnce(dayStartMs, dayEndMs)
            val totalCalReadings    = totalCalorieReadingDao.getReadingsInRangeOnce(dayStartMs, dayEndMs)
            val bpReadings          = bloodPressureReadingDao.getReadingsInRangeOnce(dayStartMs, dayEndMs) // BP-GLUCOSE-SUMMARY
            val glucoseReadings     = glucoseReadingDao.getReadingsInRangeOnce(dayStartMs, dayEndMs)        // BP-GLUCOSE-SUMMARY
            val session             = sleepSessionDao.getSessionForDateOnce(date)
            val stages              = if (session != null) sleepStageDao.getStagesForSessionOnce(session.id) else emptyList()

            // ── HR ────────────────────────────────────────────────────────────
            val avgHrBpm     = hrReadings.averageOrNull { it.bpm.toDouble() }
            val restingHrBpm = hrReadings
                .filter { it.recordedAt.toEpochMilli() in dayStartMs until nightEndMs }
                .groupBy { it.recordedAt.toEpochMilli() / 300_000L }
                .values
                .map { bucket -> bucket.map { it.bpm }.average() }
                .minOrNull()

            // ── HRV ───────────────────────────────────────────────────────────
            val avgHrvMs     = hrvReadings.averageOrNull { it.rmssdMs }
            val hrvMinMs     = hrvReadings.minByOrNull { it.rmssdMs }?.rmssdMs
            val hrvMaxMs     = hrvReadings.maxByOrNull { it.rmssdMs }?.rmssdMs
            // Deliberately keyed off the sleep session's own timestamps (via
            // OvernightHrvCalculator), not dayStartMs/dayEndMs — do not
            // "simplify" this into a calendar-day query, the overnight
            // window can start the evening before `date`.
            val overnightHrvMs = overnightHrvCalculator.calculate(date)

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
            // STEPS-MODE: DELTA = sum all interval readings; ABSOLUTE = latest reading is the total.
            Timber.tag(TAG).d("[STAGE-7 SUMMARY-WORKER] stepsMode=%s readings=%d", stepsMode, stepsReadings.size) // STEPS-MODE / DPT
            val steps = when (stepsMode) { // STEPS-MODE
                StepsMode.DELTA -> stepsReadings.sumOf { it.cumulativeSteps }.takeIf { stepsReadings.isNotEmpty() } // STEPS-MODE
                StepsMode.ABSOLUTE -> stepsReadings.maxByOrNull { it.recordedAt }?.cumulativeSteps // STEPS-MODE
            }
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

            // ── Blood pressure ────────────────────────────────────────────────── BP-GLUCOSE-SUMMARY
            val avgSystolicMmHg  = bpReadings.takeIf { it.isNotEmpty() } // BP-GLUCOSE-SUMMARY
                ?.map { it.systolic.toDouble() }?.average()?.takeIf { it.isFinite() } // BP-GLUCOSE-SUMMARY
            val avgDiastolicMmHg = bpReadings.takeIf { it.isNotEmpty() } // BP-GLUCOSE-SUMMARY
                ?.map { it.diastolic.toDouble() }?.average()?.takeIf { it.isFinite() } // BP-GLUCOSE-SUMMARY

            // ── Glucose (normalised to mmol/L) ───────────────────────────────── BP-GLUCOSE-SUMMARY
            val avgGlucoseMmolL  = glucoseReadings.takeIf { it.isNotEmpty() } // BP-GLUCOSE-SUMMARY
                ?.map { r -> if (r.unit == "mmol") r.value else r.value / 18.0182 } // BP-GLUCOSE-SUMMARY
                ?.average()?.takeIf { it.isFinite() } // BP-GLUCOSE-SUMMARY

            Timber.tag(TAG).d("[STAGE-7 SUMMARY-WORKER] bloodPressure readings=%d avgSystolic=%s avgDiastolic=%s", // BP-GLUCOSE-SUMMARY
                bpReadings.size, avgSystolicMmHg, avgDiastolicMmHg) // BP-GLUCOSE-SUMMARY
            Timber.tag(TAG).d("[STAGE-7 SUMMARY-WORKER] glucose readings=%d avgGlucose=%s", // BP-GLUCOSE-SUMMARY
                glucoseReadings.size, avgGlucoseMmolL) // BP-GLUCOSE-SUMMARY

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
                totalCalReadings.map { it.source } +
                bpReadings.map { it.source } + // BP-GLUCOSE-SUMMARY
                glucoseReadings.map { it.source } // BP-GLUCOSE-SUMMARY
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
                    overnightHrvMs     = overnightHrvMs,
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
                    avgSystolicMmHg    = avgSystolicMmHg,  // BP-GLUCOSE-SUMMARY
                    avgDiastolicMmHg   = avgDiastolicMmHg, // BP-GLUCOSE-SUMMARY
                    avgGlucoseMmolL    = avgGlucoseMmolL,  // BP-GLUCOSE-SUMMARY
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

            // metric_daily_stats: per-day, point-in-time write anchored to this worker's own
            // `date`/`zone` (never now()).
            metricDailyStatsWriter.write(date, zone)

            Timber.tag(TAG).d("[STAGE-7 SUMMARY-WORKER] complete — avgHr=%s restingHr=%s avgHrv=%s steps=%s sleepMin=%s", // DPT
                avgHrBpm, restingHrBpm, avgHrvMs, steps, sleepMinutes) // DPT
            Result.success()
        } catch (e: NullPointerException) {
            Timber.tag(TAG).e(e, "[STAGE-7 SUMMARY-WORKER] programmer error for date=%s — failing permanently", dateStr) // DPT
            Result.failure()
        } catch (e: IllegalStateException) {
            Timber.tag(TAG).e(e, "[STAGE-7 SUMMARY-WORKER] programmer error for date=%s — failing permanently", dateStr) // DPT
            Result.failure()
        } catch (e: Exception) {
            Timber.tag(TAG).e("[STAGE-7 SUMMARY-WORKER] ERROR — %s", e.message) // DPT
            Timber.e(e, "DailySummaryWorker failed for date $dateStr")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "data-pathway-tracker" // DPT
        const val KEY_DATE = "date"
        const val KEY_STEPS_MODE = "steps_mode" // STEPS-MODE
    }
}

/**
 * Enqueues a [DailySummaryWorker] for [date] with REPLACE conflict strategy.
 *
 * REPLACE means if a job for the same date is already queued or running it
 * is cancelled and replaced — safe because the worker is fully idempotent.
 */
fun enqueueSummaryWorker(
    date: LocalDate,
    workManager: WorkManager,
    stepsMode: StepsMode = StepsMode.DELTA, // STEPS-MODE: BleEngine passes manifest value; other callers omit → DELTA
) {
    val request = OneTimeWorkRequestBuilder<DailySummaryWorker>()
        .setInputData(workDataOf(
            DailySummaryWorker.KEY_DATE to date.toString(),
            DailySummaryWorker.KEY_STEPS_MODE to stepsMode.name, // STEPS-MODE
        ))
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
