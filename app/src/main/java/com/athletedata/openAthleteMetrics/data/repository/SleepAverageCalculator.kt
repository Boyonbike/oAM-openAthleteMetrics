package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.model.BaselineMetric
import com.athletedata.openAthleteMetrics.data.model.DailySummary
import com.athletedata.openAthleteMetrics.data.model.SleepAverages
import com.athletedata.openAthleteMetrics.data.model.SleepSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

private const val MINUTES_PER_DAY = 1440
private const val NOON_MINUTES = 720

/**
 * Plain (non-baseline-band) historical averages for the Sleep card: total duration,
 * fall-asleep/wake time, and per-stage duration/percentage. Each average resolves its own
 * window/minimum via [BaselineWindowConfigRepository] (override → [com.athletedata.openAthleteMetrics.data.model.BaselineFallbackDefaults]
 * → global setting), so a user override on one sleep metric doesn't affect the others.
 *
 * Deliberately separate from [BaselineRepository]/[RoomBaselineRepository]: these are plain
 * averages for display, not mean±SD baseline bands, and are computed on demand rather than
 * persisted or recalculated by a worker.
 */
class SleepAverageCalculator @Inject constructor(
    private val sleepRepo: SleepRepository,
    private val summaryRepo: DailySummaryRepository,
    private val windowConfigRepo: BaselineWindowConfigRepository,
) {

    private val stageMetrics = listOf(
        BaselineMetric.SLEEP_DEEP to DailySummary::sleepDeepMinutes,
        BaselineMetric.SLEEP_LIGHT to DailySummary::sleepLightMinutes,
        BaselineMetric.SLEEP_REM to DailySummary::sleepRemMinutes,
        BaselineMetric.SLEEP_AWAKE to DailySummary::sleepAwakeMinutes,
    )

    /**
     * Reactively recomputes averages whenever the underlying sessions/summaries change, rather
     * than resolving once and going stale for the life of a subscription (e.g. a ViewModel's
     * `stateIn`) — see [SleepDetailProvider.observeSleepData].
     */
    fun observe(anchorDate: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Flow<SleepAverages> = flow {
        val allMetrics = listOf(
            BaselineMetric.SLEEP, BaselineMetric.SLEEP_ONSET, BaselineMetric.SLEEP_WAKE,
        ) + stageMetrics.map { it.first }

        // metric -> (windowDays, minimumDays)
        val windows = allMetrics.associateWith { metric ->
            windowConfigRepo.getEffectiveWindow(metric) to windowConfigRepo.getEffectiveMinimum(metric)
        }

        val maxWindowDays = windows.values.maxOf { it.first }
        val rangeStart = anchorDate.minusDays((maxWindowDays - 1).toLong())

        emitAll(
            combine(
                sleepRepo.getSessionsForRange(rangeStart, anchorDate),
                summaryRepo.getSummariesForRange(rangeStart, anchorDate),
            ) { sessions, summaries ->
                buildAverages(anchorDate, zone, windows, sessions, summaries)
            }
        )
    }

    private fun buildAverages(
        anchorDate: LocalDate,
        zone: ZoneId,
        windows: Map<BaselineMetric, Pair<Int, Int>>,
        sessions: List<SleepSession>,
        summaries: List<DailySummary>,
    ): SleepAverages {
        val avgTotalMinutes = run {
            val (windowDays, minDays) = windows.getValue(BaselineMetric.SLEEP)
            val cutoff = anchorDate.minusDays((windowDays - 1).toLong())
            val values = sessions.filter { !it.date.isBefore(cutoff) }.map { it.durationMinutes }
            if (values.size < minDays) null else values.average().toInt()
        }

        val avgOnsetTime = run {
            val (windowDays, minDays) = windows.getValue(BaselineMetric.SLEEP_ONSET)
            val cutoff = anchorDate.minusDays((windowDays - 1).toLong())
            val instants = sessions.filter { !it.date.isBefore(cutoff) }.map { it.sleepStartMs }
            if (instants.size < minDays) null else averageTimeOfDay(instants, zone)
        }

        val avgWakeTime = run {
            val (windowDays, minDays) = windows.getValue(BaselineMetric.SLEEP_WAKE)
            val cutoff = anchorDate.minusDays((windowDays - 1).toLong())
            val instants = sessions.filter { !it.date.isBefore(cutoff) }.map { it.sleepEndMs }
            if (instants.size < minDays) null else averageTimeOfDay(instants, zone)
        }

        val deep = averageStage(BaselineMetric.SLEEP_DEEP, windows, summaries, anchorDate) { it.sleepDeepMinutes }
        val light = averageStage(BaselineMetric.SLEEP_LIGHT, windows, summaries, anchorDate) { it.sleepLightMinutes }
        val rem = averageStage(BaselineMetric.SLEEP_REM, windows, summaries, anchorDate) { it.sleepRemMinutes }
        val awake = averageStage(BaselineMetric.SLEEP_AWAKE, windows, summaries, anchorDate) { it.sleepAwakeMinutes }

        return SleepAverages(
            avgTotalMinutes = avgTotalMinutes,
            avgOnsetTime = avgOnsetTime,
            avgWakeTime = avgWakeTime,
            avgDeepMinutes = deep?.first, avgDeepPct = deep?.second,
            avgLightMinutes = light?.first, avgLightPct = light?.second,
            avgRemMinutes = rem?.first, avgRemPct = rem?.second,
            avgAwakeMinutes = awake?.first, avgAwakePct = awake?.second,
        )
    }

    /**
     * For each night in [metric]'s resolved window where both the stage's minutes and the
     * night's total sleep minutes are present, computes that night's stage percentage, then
     * averages minutes and percentages independently across nights (average-of-ratios, not
     * ratio-of-averages — keeps the sample self-consistent per night rather than mixing nights
     * where only one of the two values was recorded).
     */
    private fun averageStage(
        metric: BaselineMetric,
        windows: Map<BaselineMetric, Pair<Int, Int>>,
        summaries: List<DailySummary>,
        anchorDate: LocalDate,
        stageMinutes: (DailySummary) -> Int?,
    ): Pair<Int, Double>? {
        val (windowDays, minDays) = windows.getValue(metric)
        val cutoff = anchorDate.minusDays((windowDays - 1).toLong())
        val nights = summaries
            .filter { !it.date.isBefore(cutoff) }
            .mapNotNull { summary ->
                val stage = stageMinutes(summary) ?: return@mapNotNull null
                // Denominator is this same night's own four stage fields, not the separately
                // sourced summary.sleepMinutes — guarantees stage/total can never exceed 100%,
                // regardless of any staleness in the session duration a stage sum is compared
                // against (see SleepStagePromoter's existing-session span-extension fix).
                val total = listOfNotNull(
                    summary.sleepDeepMinutes, summary.sleepLightMinutes,
                    summary.sleepRemMinutes, summary.sleepAwakeMinutes,
                ).sum()
                if (total <= 0) return@mapNotNull null
                stage to (stage * 100.0 / total)
            }
        if (nights.size < minDays) return null
        val avgMinutes = nights.map { it.first }.average()
        val avgPct = nights.map { it.second }.average()
        return avgMinutes.toInt() to avgPct
    }

    /**
     * Averages a set of clock times by shifting the reference point to noon rather than
     * midnight, so a fall-asleep/wake-time average doesn't break near the midnight wraparound
     * (e.g. 23:50 and 00:10 should average to ~00:00, not ~12:00). A full circular/vector
     * (sin/cos) mean isn't needed here: sleep onset/wake times never cluster near noon in
     * practice, so there's no risk of the "opposite points cancel out" failure mode that
     * circular mean exists to handle.
     */
    private fun averageTimeOfDay(instants: List<Instant>, zone: ZoneId): LocalTime {
        val shifted = instants.map { instant ->
            val minuteOfDay = instant.atZone(zone).let { it.hour * 60 + it.minute }
            (minuteOfDay - NOON_MINUTES + MINUTES_PER_DAY) % MINUTES_PER_DAY
        }
        val avgMinuteOfDay = ((shifted.average() + NOON_MINUTES) % MINUTES_PER_DAY + MINUTES_PER_DAY) % MINUTES_PER_DAY
        return LocalTime.of((avgMinuteOfDay / 60).toInt(), (avgMinuteOfDay % 60).toInt())
    }
}
