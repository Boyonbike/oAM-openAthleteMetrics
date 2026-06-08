package com.athletedata.openAthleteMetrics.seeder

import androidx.work.WorkManager
import com.athletedata.openAthleteMetrics.data.db.QuestionDefinitionDao
import com.athletedata.openAthleteMetrics.data.db.QuestionDefinitionEntity
import com.athletedata.openAthleteMetrics.data.db.QuestionResponseDao
import com.athletedata.openAthleteMetrics.data.db.QuestionResponseEntity
import com.athletedata.openAthleteMetrics.data.model.DailyContext
import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.data.model.MetricReading
import com.athletedata.openAthleteMetrics.data.model.MetricType
import com.athletedata.openAthleteMetrics.data.model.SleepSession
import com.athletedata.openAthleteMetrics.data.model.SleepStage
import com.athletedata.openAthleteMetrics.data.repository.DailyContextRepository
import com.athletedata.openAthleteMetrics.data.repository.DailySummaryRepository
import com.athletedata.openAthleteMetrics.data.repository.MetricRepository
import com.athletedata.openAthleteMetrics.data.repository.SleepRepository
import com.athletedata.openAthleteMetrics.worker.enqueueSummaryWorker
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Random
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

@Singleton
class SeederService @Inject constructor(
    private val metricRepository: MetricRepository,
    private val sleepRepository: SleepRepository,
    private val dailyContextRepository: DailyContextRepository,
    private val dailySummaryRepository: DailySummaryRepository,
    private val questionDefinitionDao: QuestionDefinitionDao,
    private val questionResponseDao: QuestionResponseDao,
    private val workManager: WorkManager,
) {
    // A new Random(42) is created at the start of each seed call to guarantee
    // that every call to seedDays(N) produces identical output.

    suspend fun seedDays(days: Int, onProgress: (Float) -> Unit) {
        val rng = Random(42L)
        val today = LocalDate.now()
        val dates = (days - 1 downTo 0).map { today.minusDays(it.toLong()) }

        val workoutDays = computeWorkoutDays(dates, rng)
        val illnessDays = computeIllnessDays(dates, rng)
        val dipNights   = computeDipNights(dates, rng)
        val sleepMins   = computeSleepDurations(dates, rng)
        val weights     = computeWeightSeries(dates, rng)

        val lifestyleDefinitions = questionDefinitionDao.getLifestyleOnce()

        val readings  = mutableListOf<MetricReading>()
        val sessions  = mutableListOf<SleepSession>()
        val contexts  = mutableListOf<DailyContext>()
        val responses = mutableListOf<QuestionResponseEntity>()

        for ((idx, date) in dates.withIndex()) {
            val alreadySeeded = metricRepository.hasSeederReadingsForDateOnce(date)
                || sleepRepository.getSessionForDateOnce(date) != null
                || dailyContextRepository.getForDateOnce(date) != null

            if (!alreadySeeded) {
                readings += generateHr(date, date in workoutDays, rng)
                readings += generateHrv(date, rng)
                readings += generateSpo2(date, date in dipNights, rng)
                readings += generateSteps(date, date in workoutDays, rng)
                sessions += generateSleepSession(date, sleepMins.getValue(date), rng)

                // morningHrv is extracted here so it can be shared with question response generation.
                val weeklyAdj = weeklyHrvAdjustment(date)
                val morningHrv = (60.0 + weeklyAdj + rng.nextGaussian() * 5.0).coerceIn(20.0, 100.0)

                contexts += generateDailyContext(
                    date, date in illnessDays, weights.getValue(date), sleepMins.getValue(date), morningHrv, rng
                )
                if (lifestyleDefinitions.isNotEmpty()) {
                    responses += generateQuestionResponses(
                        date, morningHrv, sleepMins.getValue(date), rng, lifestyleDefinitions
                    )
                }
            }
            onProgress(idx.toFloat() / dates.size * 0.70f)
        }

        if (readings.isNotEmpty()) metricRepository.insertAll(readings)
        onProgress(0.78f)

        for (session in sessions) sleepRepository.insert(session)
        onProgress(0.85f)

        for (ctx in contexts) dailyContextRepository.upsert(ctx)
        onProgress(0.90f)

        if (responses.isNotEmpty()) questionResponseDao.upsertAll(responses)
        onProgress(0.95f)

        val seededDates = (readings.map {
            it.recordedAt.atZone(ZoneOffset.UTC).toLocalDate()
        } + sessions.map { it.date }).distinct()
        seededDates.forEach { enqueueSummaryWorker(it, workManager) }
        onProgress(1.00f)
    }

    suspend fun clearSeederData(onProgress: (Float) -> Unit) {
        metricRepository.deleteBySource(DataSource.SEEDER)
        onProgress(0.33f)
        sleepRepository.deleteBySource(DataSource.SEEDER)
        onProgress(0.66f)
        dailySummaryRepository.deleteAll()
        onProgress(1.00f)
    }

    // ── Schedule pre-computation ──────────────────────────────────────────────

    private fun computeWorkoutDays(dates: List<LocalDate>, rng: Random): Set<LocalDate> {
        val result = mutableSetOf<LocalDate>()
        var i = 0
        while (i < dates.size) {
            val weekEnd = minOf(i + 7, dates.size)
            val count = rng.nextInt(2) + 1
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

    private fun generateHr(date: LocalDate, hasWorkout: Boolean, rng: Random): List<MetricReading> {
        val dayStartMs = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val workoutStartMin = if (hasWorkout) rng.nextInt(12 * 60) + 7 * 60 else -1
        val workoutEndMin   = if (hasWorkout) workoutStartMin + rng.nextInt(31) + 30 else -1
        val now = Instant.now()

        return (0 until 24 * 60 step 5).map { min ->
            val hour = min / 60
            val inWorkout = hasWorkout && min in workoutStartMin until workoutEndMin
            val base = when {
                inWorkout -> rng.nextDouble() * 35.0 + 130.0
                hour in 0..5 || hour >= 22 -> rng.nextDouble() * 14.0 + 48.0
                else -> rng.nextDouble() * 20.0 + 60.0
            }
            MetricReading(
                metricType = MetricType.HR,
                value      = (base + rng.nextGaussian() * 3.0).coerceIn(30.0, 220.0),
                unit       = "bpm",
                recordedAt = Instant.ofEpochMilli(dayStartMs + min * 60_000L),
                createdAt  = now,
                source     = DataSource.SEEDER,
            )
        }
    }

    // ── HRV ───────────────────────────────────────────────────────────────────

    private fun generateHrv(date: LocalDate, rng: Random): List<MetricReading> {
        val dayStartMs = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
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
            val hrv = (60.0 + weeklyAdj + circadianAdj + rng.nextGaussian() * 5.0)
                .coerceIn(20.0, 100.0)
            MetricReading(
                metricType = MetricType.HRV,
                value      = hrv,
                unit       = "ms",
                recordedAt = Instant.ofEpochMilli(dayStartMs + hour * 3_600_000L),
                createdAt  = now,
                source     = DataSource.SEEDER,
            )
        }
    }

    // ── SpO2 ──────────────────────────────────────────────────────────────────

    private fun generateSpo2(date: LocalDate, isDipNight: Boolean, rng: Random): List<MetricReading> {
        val dayStartMs = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val now = Instant.now()

        return listOf(0, 1, 2, 3, 4, 5, 6, 7, 22, 23).map { hour ->
            val base = if (isDipNight && hour in 2..4) {
                rng.nextDouble() * 2.0 + 93.0
            } else {
                rng.nextDouble() * 4.0 + 95.0
            }
            MetricReading(
                metricType = MetricType.SPO2,
                value      = (base + rng.nextGaussian() * 0.5).coerceIn(88.0, 100.0),
                unit       = "%",
                recordedAt = Instant.ofEpochMilli(dayStartMs + hour * 3_600_000L),
                createdAt  = now,
                source     = DataSource.SEEDER,
            )
        }
    }

    // ── Steps ─────────────────────────────────────────────────────────────────

    private fun generateSteps(date: LocalDate, hasWorkout: Boolean, rng: Random): List<MetricReading> {
        val base = when {
            hasWorkout -> rng.nextDouble() * 6_000.0 + 8_000.0
            date.dayOfWeek.value >= 6 -> rng.nextDouble() * 6_000.0 + 4_000.0
            else -> rng.nextDouble() * 6_000.0 + 6_000.0
        }
        return listOf(
            MetricReading(
                metricType = MetricType.STEPS,
                value      = (base + rng.nextGaussian() * 500.0).coerceIn(1_000.0, 25_000.0),
                unit       = "steps",
                recordedAt = date.atTime(23, 59).toInstant(ZoneOffset.UTC),
                createdAt  = Instant.now(),
                source     = DataSource.SEEDER,
            )
        )
    }

    // ── Sleep session ─────────────────────────────────────────────────────────

    private fun generateSleepSession(date: LocalDate, durationMinutes: Int, rng: Random): SleepSession {
        val wakeHour = rng.nextInt(3) + 6
        val endMs    = date.atTime(wakeHour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val startMs  = endMs - durationMinutes * 60_000L
        return SleepSession(
            date           = date,
            sleepStartMs   = Instant.ofEpochMilli(startMs),
            sleepEndMs     = Instant.ofEpochMilli(endMs),
            durationMinutes = durationMinutes,
            stagesJson     = buildStagesJson(startMs, endMs),
            source         = DataSource.SEEDER,
        )
    }

    private fun buildStagesJson(startMs: Long, endMs: Long): String {
        // Each 90-min cycle: LIGHT(15%) → DEEP(20%) → LIGHT(15%) → REM(25%) → LIGHT(20%) → AWAKE(5%)
        val cycleMs = 90 * 60_000L
        val cyclePattern = listOf(
            SleepStage.LIGHT to 0.15,
            SleepStage.DEEP  to 0.20,
            SleepStage.LIGHT to 0.15,
            SleepStage.REM   to 0.25,
            SleepStage.LIGHT to 0.20,
            SleepStage.AWAKE to 0.05,
        )
        val arr = JSONArray()
        var cur = startMs
        while (cur < endMs) {
            for ((stage, proportion) in cyclePattern) {
                if (cur >= endMs) break
                val blockEnd = minOf(cur + (cycleMs * proportion).toLong(), endMs)
                arr.put(JSONObject().apply {
                    put("stage",   stage.name)
                    put("startMs", cur)
                    put("endMs",   blockEnd)
                })
                cur = blockEnd
            }
        }
        return arr.toString()
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
        definitions: List<QuestionDefinitionEntity>,
    ): List<QuestionResponseEntity> {
        val now        = Instant.now().toEpochMilli()
        val dateStr    = date.toString()
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
                date       = dateStr,
                value      = value.toString(),
                recordedAt = now,
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
