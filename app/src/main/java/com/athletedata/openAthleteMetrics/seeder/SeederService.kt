package com.athletedata.openAthleteMetrics.seeder

import androidx.room.withTransaction
import androidx.work.WorkManager
import com.athletedata.openAthleteMetrics.data.db.ActiveCalorieReadingEntity
import com.athletedata.openAthleteMetrics.data.db.AppDatabase
import com.athletedata.openAthleteMetrics.data.db.BloodPressureReadingEntity
import com.athletedata.openAthleteMetrics.data.db.GlucoseReadingEntity
import com.athletedata.openAthleteMetrics.data.db.HrReadingEntity
import com.athletedata.openAthleteMetrics.data.db.HrvReadingEntity
import com.athletedata.openAthleteMetrics.data.db.QuestionResponseEntity
import com.athletedata.openAthleteMetrics.data.db.RespirationReadingEntity
import com.athletedata.openAthleteMetrics.data.db.SkinTempReadingEntity
import com.athletedata.openAthleteMetrics.data.db.SleepStageEntity
import com.athletedata.openAthleteMetrics.data.db.SpO2ReadingEntity
import com.athletedata.openAthleteMetrics.data.db.StepsReadingEntity
import com.athletedata.openAthleteMetrics.data.db.TotalCalorieReadingEntity
import com.athletedata.openAthleteMetrics.data.model.Activity
import com.athletedata.openAthleteMetrics.data.model.QuestionDefinition
import com.athletedata.openAthleteMetrics.data.model.DailyContext
import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.data.model.SleepSession
import com.athletedata.openAthleteMetrics.data.model.SleepStage
import com.athletedata.openAthleteMetrics.data.repository.ActiveCalorieReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.ActivityRepository
import com.athletedata.openAthleteMetrics.data.repository.BloodPressureReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.DailyContextRepository
import com.athletedata.openAthleteMetrics.data.repository.DailySummaryRepository
import com.athletedata.openAthleteMetrics.data.repository.GlucoseReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.HrReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.HrvReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.RespirationReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.SkinTempReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.SleepRepository
import com.athletedata.openAthleteMetrics.data.repository.SleepStageRepository
import com.athletedata.openAthleteMetrics.data.repository.SpO2ReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.StepsReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.QuestionRepository
import com.athletedata.openAthleteMetrics.data.repository.TotalCalorieReadingRepository
import com.athletedata.openAthleteMetrics.worker.enqueueSummaryWorker
import org.json.JSONObject
import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Random
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

@Singleton
class SeederService @Inject constructor(
    private val hrReadingRepository: HrReadingRepository,
    private val hrvReadingRepository: HrvReadingRepository,
    private val spo2ReadingRepository: SpO2ReadingRepository,
    private val skinTempReadingRepository: SkinTempReadingRepository,
    private val respirationReadingRepository: RespirationReadingRepository,
    private val stepsReadingRepository: StepsReadingRepository,
    private val activeCalorieReadingRepository: ActiveCalorieReadingRepository,
    private val totalCalorieReadingRepository: TotalCalorieReadingRepository,
    private val bloodPressureReadingRepository: BloodPressureReadingRepository,
    private val glucoseReadingRepository: GlucoseReadingRepository,
    private val sleepRepository: SleepRepository,
    private val sleepStageRepository: SleepStageRepository,
    private val dailyContextRepository: DailyContextRepository,
    private val dailySummaryRepository: DailySummaryRepository,
    private val activityRepository: ActivityRepository,
    private val questionRepository: QuestionRepository,
    private val workManager: WorkManager,
    private val appDatabase: AppDatabase,
) {
    suspend fun seedThirtyDays(onProgress: (Float) -> Unit): List<LocalDate> = seedDays(30, onProgress)

    suspend fun seedToday(onProgress: (Float) -> Unit): List<LocalDate> = seedDays(1, onProgress)

    suspend fun clearSeederData(onProgress: (Float) -> Unit) {
        hrReadingRepository.deleteBySource(DataSource.SEEDER)
        onProgress(0.10f)
        hrvReadingRepository.deleteBySource(DataSource.SEEDER)
        onProgress(0.20f)
        spo2ReadingRepository.deleteBySource(DataSource.SEEDER)
        onProgress(0.30f)
        skinTempReadingRepository.deleteBySource(DataSource.SEEDER)
        onProgress(0.40f)
        respirationReadingRepository.deleteBySource(DataSource.SEEDER)
        onProgress(0.50f)
        stepsReadingRepository.deleteBySource(DataSource.SEEDER)
        onProgress(0.55f)
        activeCalorieReadingRepository.deleteBySource(DataSource.SEEDER)
        onProgress(0.60f)
        totalCalorieReadingRepository.deleteBySource(DataSource.SEEDER)
        onProgress(0.63f)
        bloodPressureReadingRepository.deleteBySource(DataSource.SEEDER)
        onProgress(0.66f)
        glucoseReadingRepository.deleteBySource(DataSource.SEEDER)
        onProgress(0.70f)
        sleepStageRepository.deleteBySource(DataSource.SEEDER)
        onProgress(0.75f)
        sleepRepository.deleteBySource(DataSource.SEEDER)
        onProgress(0.80f)
        activityRepository.deleteBySource(DataSource.SEEDER)
        onProgress(0.90f)
        dailySummaryRepository.deleteAll()
        onProgress(0.93f)
        questionRepository.deleteAllResponses()
        onProgress(0.97f)
        dailyContextRepository.deleteAll()
        onProgress(1.00f)
    }

    // ── Core orchestration ────────────────────────────────────────────────────

    private suspend fun seedDays(days: Int, onProgress: (Float) -> Unit): List<LocalDate> {
        val rng   = Random(42L)
        val today = LocalDate.now()
        val dates = (days - 1 downTo 0).map { today.minusDays(it.toLong()) }

        val workoutDays = computeWorkoutDays(dates, rng)
        val illnessDays = computeIllnessDays(dates, rng)
        val dipNights   = computeDipNights(dates, rng)
        val sleepMins   = computeSleepDurations(dates, rng)
        val weights     = computeWeightSeries(dates, rng)

        val lifestyleDefinitions = questionRepository.getLifestyleQuestionsOnce()
        val failedDates = mutableListOf<LocalDate>()

        for ((idx, date) in dates.withIndex()) {
            val dayStartMs = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val dayEndMs   = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

            val alreadySeeded = hrReadingRepository.getReadingsInRangeOnce(dayStartMs, dayEndMs).isNotEmpty()
                || sleepRepository.getSessionForDateOnce(date) != null
                || dailyContextRepository.getForDateOnce(date) != null

            if (!alreadySeeded) {
                try {
                    val hasWorkout      = date in workoutDays
                    val workoutStartMin = if (hasWorkout) rng.nextInt(12 * 60) + 7 * 60 else -1
                    val workoutEndMin   = if (hasWorkout) workoutStartMin + rng.nextInt(31) + 30 else -1

                    appDatabase.withTransaction {
                        hrReadingRepository.insertAll(generateHr(date, workoutStartMin, workoutEndMin, rng))
                        hrvReadingRepository.insertAll(generateHrv(date, rng))
                        spo2ReadingRepository.insertAll(generateSpo2(date, date in dipNights, rng))
                        skinTempReadingRepository.insertAll(generateSkinTemp(date, rng))
                        respirationReadingRepository.insertAll(generateRespiration(date, workoutStartMin, workoutEndMin, rng))
                        stepsReadingRepository.insertAll(generateSteps(date, hasWorkout, rng))
                        generateActiveCalories(date, hasWorkout, workoutStartMin, workoutEndMin, rng)
                            ?.let { activeCalorieReadingRepository.insertAll(listOf(it)) }
                        totalCalorieReadingRepository.insertAll(listOf(generateTotalCalories(date, hasWorkout, rng)))
                        bloodPressureReadingRepository.insertAll(generateBloodPressure(date, rng))
                        glucoseReadingRepository.insertAll(generateGlucose(date, rng))

                        val weeklyAdj  = weeklyHrvAdjustment(date)
                        val morningHrv = (60.0 + weeklyAdj + rng.nextGaussian() * 5.0).coerceIn(20.0, 100.0)

                        val durationMins = sleepMins.getValue(date)
                        val wakeHour  = rng.nextInt(3) + 6
                        val endMs     = date.atTime(wakeHour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        val startMs   = endMs - durationMins * 60_000L
                        val session   = SleepSession(
                            date            = date,
                            sleepStartMs    = Instant.ofEpochMilli(startMs),
                            sleepEndMs      = Instant.ofEpochMilli(endMs),
                            durationMinutes = durationMins,
                            source          = DataSource.SEEDER,
                        )
                        sleepRepository.insert(session)
                        val inserted = sleepRepository.getSessionForDateOnce(date)
                        if (inserted != null) {
                            sleepStageRepository.insertAll(buildSleepStages(inserted.id, startMs, endMs, rng))
                        }

                        dailyContextRepository.upsert(generateDailyContext(
                            date, date in illnessDays, weights.getValue(date),
                            durationMins, morningHrv, rng,
                        ))
                        if (lifestyleDefinitions.isNotEmpty()) {
                            questionRepository.upsertAllResponses(generateQuestionResponses(
                                date, morningHrv, durationMins, rng, lifestyleDefinitions,
                            ))
                        }
                        activityRepository.insertAll(listOf(generateActivity(date, hasWorkout, rng)))
                    }

                    enqueueSummaryWorker(date, workManager)
                } catch (e: Exception) {
                    Timber.e(e, "Seeding failed for date=$date, skipping")
                    failedDates.add(date)
                }
            }
            onProgress((idx + 1).toFloat() / dates.size)
        }

        return failedDates
    }

    // ── Schedule pre-computation ──────────────────────────────────────────────

    private fun computeWorkoutDays(dates: List<LocalDate>, rng: Random): Set<LocalDate> {
        val result = mutableSetOf<LocalDate>()
        var i = 0
        while (i < dates.size) {
            val weekEnd = minOf(i + 7, dates.size)
            val count   = rng.nextInt(2) + 1
            val indices = (i until weekEnd).toMutableList()
            repeat(minOf(count, indices.size)) {
                val pick = rng.nextInt(indices.size)
                result.add(dates[indices[pick]])
                indices.removeAt(pick)
            }
            i += 7
        }
        return result
    }

    private fun computeIllnessDays(dates: List<LocalDate>, rng: Random): Set<LocalDate> {
        if (dates.size < 5) return emptySet()
        val duration = rng.nextInt(2) + 2
        val startIdx = rng.nextInt(dates.size - duration - 2) + 2
        return (0 until duration).map { dates[startIdx + it] }.toSet()
    }

    private fun computeDipNights(dates: List<LocalDate>, rng: Random): Set<LocalDate> {
        val result = mutableSetOf<LocalDate>()
        var i = 0
        while (i < dates.size) {
            result.add(dates[i + rng.nextInt(minOf(7, dates.size - i))])
            i += 7
        }
        return result
    }

    private fun computeSleepDurations(dates: List<LocalDate>, rng: Random): Map<LocalDate, Int> =
        dates.associateWith { ((rng.nextDouble() * 3.0 + 6.0) * 60).toInt() }

    private fun computeWeightSeries(dates: List<LocalDate>, rng: Random): Map<LocalDate, Double> {
        val map = mutableMapOf<LocalDate, Double>()
        var w = rng.nextDouble() * 4.0 + 70.0
        for (date in dates) {
            map[date] = (w * 10.0).roundToInt() / 10.0
            w += rng.nextGaussian() * 0.3 - 0.003
            w = w.coerceIn(68.0, 76.0)
        }
        return map
    }

    // ── HR ────────────────────────────────────────────────────────────────────

    private fun generateHr(
        date: LocalDate,
        workoutStartMin: Int,
        workoutEndMin: Int,
        rng: Random,
    ): List<HrReadingEntity> {
        val dayStartMs = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val now        = Instant.now()
        val hasWorkout = workoutStartMin >= 0

        return (0 until 24 * 60 step 5).map { min ->
            val hour      = min / 60
            val inWorkout = hasWorkout && min in workoutStartMin until workoutEndMin
            val base = when {
                inWorkout                        -> rng.nextDouble() * 35.0 + 130.0
                hour in 0..5 || hour >= 22       -> rng.nextDouble() * 14.0 + 48.0
                else                             -> rng.nextDouble() * 20.0 + 60.0
            }
            val bpm = (base + rng.nextGaussian() * 3.0).coerceIn(30.0, 220.0).roundToInt()
            HrReadingEntity(
                recordedAt = Instant.ofEpochMilli(dayStartMs + min * 60_000L),
                createdAt  = now,
                source     = DataSource.SEEDER,
                bpm        = bpm,
            )
        }
    }

    // ── HRV ───────────────────────────────────────────────────────────────────

    private fun generateHrv(date: LocalDate, rng: Random): List<HrvReadingEntity> {
        val dayStartMs = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val weeklyAdj  = weeklyHrvAdjustment(date)
        val now        = Instant.now()

        return (0 until 24).map { hour ->
            val circadianAdj = when (hour) {
                in 0..6   ->  8.0
                in 7..10  ->  3.0
                in 11..15 -> -3.0
                in 16..20 ->  0.0
                else      ->  5.0
            }
            val rmssd = (60.0 + weeklyAdj + circadianAdj + rng.nextGaussian() * 5.0)
                .coerceIn(45.0, 75.0)
            HrvReadingEntity(
                recordedAt        = Instant.ofEpochMilli(dayStartMs + hour * 3_600_000L),
                createdAt         = now,
                source            = DataSource.SEEDER,
                confidence        = 0.8f,
                rmssdMs           = rmssd,
                computedByVersion = 1,
            )
        }
    }

    // ── SpO2 ──────────────────────────────────────────────────────────────────

    private fun generateSpo2(
        date: LocalDate,
        isDipNight: Boolean,
        rng: Random,
    ): List<SpO2ReadingEntity> {
        val dayStartMs = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val now        = Instant.now()

        return listOf(0, 1, 2, 3, 4, 5, 6, 7, 22, 23).map { hour ->
            val base = if (isDipNight && hour in 2..4) {
                rng.nextDouble() * 2.0 + 93.0
            } else {
                rng.nextDouble() * 4.0 + 95.0
            }
            SpO2ReadingEntity(
                recordedAt = Instant.ofEpochMilli(dayStartMs + hour * 3_600_000L),
                createdAt  = now,
                source     = DataSource.SEEDER,
                percentage = (base + rng.nextGaussian() * 0.5).coerceIn(88.0, 100.0),
            )
        }
    }

    // ── Skin temperature ──────────────────────────────────────────────────────

    private fun generateSkinTemp(date: LocalDate, rng: Random): List<SkinTempReadingEntity> {
        val dayStartMs = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val now        = Instant.now()
        val count      = rng.nextInt(5) + 4  // 4–8 readings

        // Pick distinct hours, then sort to get natural order
        val hours = (0 until 24).toMutableList()
        val pickedHours = (0 until count).map {
            val idx = rng.nextInt(hours.size)
            hours.removeAt(idx)
        }.sorted()

        return pickedHours.map { hour ->
            val overnightBias = if (hour in 0..6 || hour >= 22) -0.5 else 0.0
            val celsius = (rng.nextDouble() * 2.5 + 34.0 + overnightBias + rng.nextGaussian() * 0.2)
                .coerceIn(33.0, 37.5)
            SkinTempReadingEntity(
                recordedAt = Instant.ofEpochMilli(dayStartMs + hour * 3_600_000L),
                createdAt  = now,
                source     = DataSource.SEEDER,
                celsius    = celsius,
            )
        }
    }

    // ── Respiration ───────────────────────────────────────────────────────────

    private fun generateRespiration(
        date: LocalDate,
        workoutStartMin: Int,
        workoutEndMin: Int,
        rng: Random,
    ): List<RespirationReadingEntity> {
        val dayStartMs = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val now        = Instant.now()
        val hasWorkout = workoutStartMin >= 0

        return (0 until 24).map { hour ->
            val minOfHour = hour * 60
            val inWorkout = hasWorkout && minOfHour in workoutStartMin until workoutEndMin
            val base = if (inWorkout) {
                rng.nextDouble() * 4.0 + 16.0  // 16–20 during workout
            } else {
                rng.nextDouble() * 6.0 + 12.0  // 12–18 normally
            }
            RespirationReadingEntity(
                recordedAt       = Instant.ofEpochMilli(dayStartMs + hour * 3_600_000L),
                createdAt        = now,
                source           = DataSource.SEEDER,
                breathsPerMinute = (base + rng.nextGaussian() * 0.5).coerceIn(8.0, 30.0),
            )
        }
    }

    // ── Steps ─────────────────────────────────────────────────────────────────

    private fun generateSteps(
        date: LocalDate,
        hasWorkout: Boolean,
        rng: Random,
    ): List<StepsReadingEntity> {
        val dayStartMs = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val now        = Instant.now()

        val target = when {
            hasWorkout                       -> (rng.nextDouble() * 6_000.0 + 8_000.0).toInt()
            date.dayOfWeek.value >= 6        -> (rng.nextDouble() * 6_000.0 + 4_000.0).toInt()
            else                             -> (rng.nextDouble() * 6_000.0 + 6_000.0).toInt()
        }

        // Waking hours 06:00–22:00 → 16 hourly readings
        val wakingHours = (6..21).toList()
        // Distribute steps with noise, then normalise to target
        val rawDeltas = wakingHours.map { (rng.nextDouble() * 2.0 + 0.5) }
        val sum       = rawDeltas.sum()
        val deltas    = rawDeltas.map { ((it / sum) * target).toInt() }

        // Fix rounding drift on the last bucket
        val remainder = target - deltas.dropLast(1).sum()
        val adjusted  = deltas.dropLast(1) + remainder.coerceAtLeast(0)

        var cumulative = 0
        return wakingHours.zip(adjusted).map { (hour, delta) ->
            cumulative += delta
            StepsReadingEntity(
                recordedAt      = Instant.ofEpochMilli(dayStartMs + hour * 3_600_000L),
                createdAt       = now,
                source          = DataSource.SEEDER,
                cumulativeSteps = cumulative,
            )
        }
    }

    // ── Active calories ───────────────────────────────────────────────────────

    private fun generateActiveCalories(
        date: LocalDate,
        hasWorkout: Boolean,
        workoutStartMin: Int,
        workoutEndMin: Int,
        rng: Random,
    ): ActiveCalorieReadingEntity? {
        if (!hasWorkout) return null
        val dayStartMs   = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val midpointMs   = dayStartMs + ((workoutStartMin + workoutEndMin) / 2) * 60_000L
        return ActiveCalorieReadingEntity(
            recordedAt = Instant.ofEpochMilli(midpointMs),
            createdAt  = Instant.now(),
            source     = DataSource.SEEDER,
            calories   = rng.nextDouble() * 400.0 + 200.0,  // 200–600
        )
    }

    // ── Total calories ────────────────────────────────────────────────────────

    private fun generateTotalCalories(
        date: LocalDate,
        hasWorkout: Boolean,
        rng: Random,
    ): TotalCalorieReadingEntity {
        val base = if (hasWorkout) {
            rng.nextDouble() * 800.0 + 2_400.0   // 2400–3200 on workout days
        } else {
            rng.nextDouble() * 600.0 + 1_800.0   // 1800–2400 on rest days
        }
        return TotalCalorieReadingEntity(
            recordedAt = date.atTime(23, 59).atZone(ZoneId.systemDefault()).toInstant(),
            createdAt  = Instant.now(),
            source     = DataSource.SEEDER,
            calories   = base,
        )
    }

    // ── Blood pressure & glucose ──────────────────────────────────────────────

    private fun generateBloodPressure(date: LocalDate, rng: Random): List<BloodPressureReadingEntity> {
        val recordedAt = date.atTime(6 + rng.nextInt(3), rng.nextInt(60)).atZone(ZoneId.systemDefault()).toInstant()
        val systolic   = rng.nextInt(21) + 110
        val diastolic  = ((systolic - 110) * 0.5 + 65 + rng.nextGaussian() * 2).roundToInt().coerceIn(65, 85)
        return listOf(
            BloodPressureReadingEntity(
                recordedAt = recordedAt,
                createdAt  = Instant.now(),
                source     = DataSource.SEEDER,
                driverId   = null,
                confidence = null,
                systolic   = systolic,
                diastolic  = diastolic,
            )
        )
    }

    private fun generateGlucose(date: LocalDate, rng: Random): List<GlucoseReadingEntity> {
        val now = Instant.now()
        val windows = listOf(6, 8, 12, 18)
        return windows.mapIndexed { idx, startHour ->
            val recordedAt = date.atTime(startHour, rng.nextInt(60)).atZone(ZoneId.systemDefault()).toInstant()
            val value = if (idx == 0) {
                4.0 + rng.nextDouble() * 1.5   // fasting: 4.0–5.5 mmol
            } else {
                5.5 + rng.nextDouble() * 2.5   // post-meal: 5.5–8.0 mmol
            }
            GlucoseReadingEntity(
                recordedAt = recordedAt,
                createdAt  = now,
                source     = DataSource.SEEDER,
                driverId   = null,
                confidence = null,
                value      = value,
                unit       = "mmol",
            )
        }
    }

    // ── Sleep stages ──────────────────────────────────────────────────────────

    private fun buildSleepStages(
        sessionId: Long,
        startMs: Long,
        endMs: Long,
        @Suppress("UNUSED_PARAMETER") rng: Random,
    ): List<SleepStageEntity> {
        // Each 90-min cycle: LIGHT(15%) → DEEP(20%) → LIGHT(15%) → REM(25%) → LIGHT(20%) → AWAKE(5%)
        val cycleMs      = 90 * 60_000L
        val cyclePattern = listOf(
            SleepStage.LIGHT  to 0.15,
            SleepStage.DEEP   to 0.20,
            SleepStage.LIGHT  to 0.15,
            SleepStage.REM    to 0.25,
            SleepStage.LIGHT  to 0.20,
            SleepStage.AWAKE  to 0.05,
        )
        val sessionMinutes = ((endMs - startMs) / 60_000L).toInt()
        val stages = mutableListOf<SleepStageEntity>()
        var cur    = startMs

        while (cur < endMs) {
            val cycleEnd    = minOf(cur + cycleMs, endMs)
            val isFullCycle = cycleEnd - cur == cycleMs
            var cycleUsed   = 0

            for ((i, pair) in cyclePattern.withIndex()) {
                val (stage, proportion) = pair
                if (cur >= endMs) break
                val isLastInPattern = i == cyclePattern.lastIndex
                val blockEnd = if (isLastInPattern && isFullCycle) {
                    cycleEnd
                } else {
                    minOf(cur + (cycleMs * proportion).toLong(), endMs)
                }
                // For the last stage of a complete cycle, absorb sub-minute remainder so
                // the cycle's duration_minutes sum equals exactly 90.
                val durationMins = if (isLastInPattern && isFullCycle) {
                    90 - cycleUsed
                } else {
                    ((blockEnd - cur) / 60_000L).toInt()
                }
                if (durationMins > 0) {
                    stages += SleepStageEntity(
                        sessionId         = sessionId,
                        stage             = stage,
                        startMs           = cur,
                        endMs             = blockEnd,
                        durationMinutes   = durationMins,
                        source            = DataSource.SEEDER,
                        computedByVersion = 1,
                    )
                    cycleUsed += durationMins
                }
                cur = blockEnd
            }
        }

        // Safety net: the last partial cycle may still have truncation error; absorb it
        // so the total duration_minutes always exactly matches the session.
        if (stages.isNotEmpty()) {
            val surplus = stages.sumOf { it.durationMinutes } - sessionMinutes
            if (surplus != 0) {
                val last = stages.removeAt(stages.lastIndex)
                stages += last.copy(durationMinutes = last.durationMinutes - surplus)
            }
        }

        return stages
    }

    // ── Daily context ─────────────────────────────────────────────────────────

    private fun generateDailyContext(
        date: LocalDate,
        isIll: Boolean,
        weight: Double,
        sleepMinutes: Int,
        morningHrv: Double,
        rng: Random,
    ): DailyContext {
        val hrvNorm    = (morningHrv - 20.0) / 80.0
        val baseStress = ((1.0 - hrvNorm) * 4.0 + 1.0)
        val fatigue    = (baseStress + rng.nextGaussian() * 0.7).roundToInt().coerceIn(1, 5)
        val stress     = (baseStress + rng.nextGaussian() * 0.7).roundToInt().coerceIn(1, 5)

        val sleepHours = sleepMinutes / 60.0
        val motivBase = when {
            sleepHours >= 8.0 -> rng.nextDouble() * 1.5 + 3.5
            sleepHours >= 7.0 -> rng.nextDouble() * 2.0 + 2.5
            sleepHours >= 6.0 -> rng.nextDouble() * 2.0 + 1.5
            else              -> rng.nextDouble() * 2.0 + 1.0
        }
        val motivation   = motivBase.roundToInt().coerceIn(1, 5)
        val sleepQuality = motivBase.roundToInt().coerceIn(1, 5)
        val performFeel  = ((motivBase + (5.0 - fatigue)) / 2.0).roundToInt().coerceIn(1, 5)

        val habitsJson = JSONObject().apply {
            put("alcohol",       rng.nextDouble() < 1.5 / 7.0)
            put("meditation",    rng.nextDouble() < 5.0 / 7.0)
            put("hydration",     rng.nextDouble() < 6.0 / 7.0)
            put("sleep_routine", rng.nextDouble() < 5.0 / 7.0)
        }.toString()

        return DailyContext(
            date            = date,
            fatigue         = if (isIll) minOf(fatigue + 1, 5) else fatigue,
            stress          = if (isIll) minOf(stress  + 1, 5) else stress,
            motivation      = if (isIll) maxOf(motivation - 1, 1) else motivation,
            sleepQuality    = sleepQuality,
            performanceFeel = if (isIll) maxOf(performFeel - 1, 1) else performFeel,
            isIll           = isIll,
            illnessNotes    = if (isIll) "Not feeling well" else null,
            habitsJson      = habitsJson,
            weightKg        = weight,
            bodyFatPct      = null,
            notes           = null,
            updatedAt       = Instant.now(),
        )
    }

    // ── Question responses ────────────────────────────────────────────────────

    private fun generateQuestionResponses(
        date: LocalDate,
        morningHrv: Double,
        sleepMinutes: Int,
        rng: Random,
        definitions: List<QuestionDefinition>,
    ): List<QuestionResponseEntity> {
        val now        = Instant.now().toEpochMilli()
        val hrvNorm    = (morningHrv - 20.0) / 80.0
        val baseStress = (1.0 - hrvNorm) * 4.0 + 1.0

        val sleepHours = sleepMinutes / 60.0
        val motivBase  = when {
            sleepHours >= 8.0 -> rng.nextDouble() * 1.5 + 3.5
            sleepHours >= 7.0 -> rng.nextDouble() * 2.0 + 2.5
            sleepHours >= 6.0 -> rng.nextDouble() * 2.0 + 1.5
            else              -> rng.nextDouble() * 2.0 + 1.0
        }

        return definitions.map { def ->
            val value = when (def.name) {
                "Fatigue", "Stress" ->
                    (baseStress + rng.nextGaussian() * 0.7).roundToInt().coerceIn(1, 5)
                "Motivation" ->
                    motivBase.roundToInt().coerceIn(1, 5)
                else ->
                    rng.nextInt(5) + 1
            }
            QuestionResponseEntity(
                questionId = def.id,
                date       = date,
                value      = value.toString(),
                recordedAt = now,
            )
        }
    }

    // ── Activity ──────────────────────────────────────────────────────────────

    private fun generateActivity(date: LocalDate, isWorkout: Boolean, rng: Random): Activity {
        val dayStartMs = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return if (isWorkout) {
            val deviceNames     = listOf("Outdoor Run", "Cycling", "Strength Training", "Swimming")
            val durationMinutes = rng.nextInt(31) + 30
            val startHour       = rng.nextInt(12) + 7
            val startMs         = dayStartMs + startHour * 3_600_000L
            Activity(
                startTime       = Instant.ofEpochMilli(startMs),
                endTime         = Instant.ofEpochMilli(startMs + durationMinutes * 60_000L),
                durationMinutes = durationMinutes,
                deviceName      = deviceNames[rng.nextInt(deviceNames.size)],
                source          = DataSource.SEEDER,
                avgHrBpm        = rng.nextDouble() * 30.0 + 130.0,
            )
        } else {
            val deviceNames     = listOf("Walking", "Daily Walk", "Commute Walk")
            val durationMinutes = rng.nextInt(21) + 10
            val startHour       = rng.nextInt(8) + 8
            val startMs         = dayStartMs + startHour * 3_600_000L
            Activity(
                startTime       = Instant.ofEpochMilli(startMs),
                endTime         = Instant.ofEpochMilli(startMs + durationMinutes * 60_000L),
                durationMinutes = durationMinutes,
                deviceName      = deviceNames[rng.nextInt(deviceNames.size)],
                source          = DataSource.SEEDER,
                avgHrBpm        = rng.nextDouble() * 20.0 + 75.0,
            )
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun weeklyHrvAdjustment(date: LocalDate): Double =
        when (date.dayOfWeek) {
            java.time.DayOfWeek.MONDAY    ->  0.0
            java.time.DayOfWeek.TUESDAY   -> -2.0
            java.time.DayOfWeek.WEDNESDAY -> -5.0
            java.time.DayOfWeek.THURSDAY  -> -5.0
            java.time.DayOfWeek.FRIDAY    -> -3.0
            java.time.DayOfWeek.SATURDAY  ->  3.0
            java.time.DayOfWeek.SUNDAY    ->  5.0
            else                          ->  0.0
        }
}
