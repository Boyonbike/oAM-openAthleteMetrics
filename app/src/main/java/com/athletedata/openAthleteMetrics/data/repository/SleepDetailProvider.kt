package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.MetricDailyStatsEntity
import com.athletedata.openAthleteMetrics.data.db.SleepStageEntity
import com.athletedata.openAthleteMetrics.data.model.BaselineMetric
import com.athletedata.openAthleteMetrics.data.model.DailySummary
import com.athletedata.openAthleteMetrics.data.model.HypnogramSegment
import com.athletedata.openAthleteMetrics.data.model.SleepAverages
import com.athletedata.openAthleteMetrics.data.model.SleepData
import com.athletedata.openAthleteMetrics.data.model.SleepSession
import com.athletedata.openAthleteMetrics.data.model.TimestampedReading
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val HISTORY_DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

/**
 * Single assembler of the fully-built [SleepData] snapshot for one night — shared by the
 * Daily Detail sleep card, the in-app dashboard's sleep widgets, and the Glance home-screen
 * sleep widgets, so none of the three consumers duplicate this query/combine logic
 * (previously inlined only in `DailyDetailViewModel`).
 */
@Singleton
class SleepDetailProvider @Inject constructor(
    private val sleepRepo: SleepRepository,
    private val sleepStageRepo: SleepStageRepository,
    private val sleepAverageCalculator: SleepAverageCalculator,
    private val summaryRepo: DailySummaryRepository,
    private val metricDailyStatsReader: MetricDailyStatsReader,
) {

    fun observeSleepData(date: LocalDate): Flow<SleepData?> {
        val sleepWithStagesFlow = sleepRepo.getSessionForDate(date)
            .flatMapLatest { session ->
                if (session == null) {
                    flowOf(null as Pair<SleepSession, List<SleepStageEntity>>?)
                } else {
                    flow {
                        val stages = sleepStageRepo.getStagesForSessionOnce(session.id)
                        emit(Pair(session, stages))
                    }
                }
            }

        val averagesFlow = observeAverages(date)
        val historyFlow = observeDurationHistory(date)

        return combine(
            sleepWithStagesFlow,
            summaryRepo.getSummaryForDate(date),
            averagesFlow,
            historyFlow,
        ) { sleepWithStages, summary, averages, history ->
            buildSleepData(sleepWithStages, summary, averages, history)
        }
    }

    suspend fun getSleepDataOnce(date: LocalDate): SleepData? = observeSleepData(date).first()

    /**
     * SLEEP/SLEEP_DEEP/SLEEP_LIGHT/SLEEP_REM/SLEEP_AWAKE read from the point-in-time
     * `metric_daily_stats` table (parity with [SleepAverageCalculator]'s live computation
     * verified by SleepDetailProviderParityTest). SLEEP_ONSET/SLEEP_WAKE have no stored row
     * (circular time-of-day means, out of scope for that table — see CalculatorModule) and
     * stay on the live calculator.
     */
    private fun observeAverages(date: LocalDate): Flow<SleepAverages> {
        val storedFlow = combine(
            metricDailyStatsReader.observeFresh(BaselineMetric.SLEEP, date),
            metricDailyStatsReader.observeFresh(BaselineMetric.SLEEP_DEEP, date),
            metricDailyStatsReader.observeFresh(BaselineMetric.SLEEP_LIGHT, date),
            metricDailyStatsReader.observeFresh(BaselineMetric.SLEEP_REM, date),
            metricDailyStatsReader.observeFresh(BaselineMetric.SLEEP_AWAKE, date),
        ) { sleep, deep, light, rem, awake -> StoredSleepStats(sleep, deep, light, rem, awake) }

        return combine(sleepAverageCalculator.observe(date), storedFlow) { live, stored ->
            SleepAverages(
                // .toInt() (truncating), not roundToInt(), to match SleepAverageCalculator's
                // own truncating average().toInt()/avgMinutes.toInt() display convention.
                avgTotalMinutes = stored.sleep?.mean?.toInt(),
                avgOnsetTime = live.avgOnsetTime,
                avgWakeTime = live.avgWakeTime,
                avgDeepMinutes = stored.deep?.mean?.toInt(), avgDeepPct = stored.deep?.meanPct,
                avgLightMinutes = stored.light?.mean?.toInt(), avgLightPct = stored.light?.meanPct,
                avgRemMinutes = stored.rem?.mean?.toInt(), avgRemPct = stored.rem?.meanPct,
                avgAwakeMinutes = stored.awake?.mean?.toInt(), avgAwakePct = stored.awake?.meanPct,
            )
        }
    }

    private data class StoredSleepStats(
        val sleep: MetricDailyStatsEntity?,
        val deep: MetricDailyStatsEntity?,
        val light: MetricDailyStatsEntity?,
        val rem: MetricDailyStatsEntity?,
        val awake: MetricDailyStatsEntity?,
    )

    private fun observeDurationHistory(date: LocalDate): Flow<List<TimestampedReading>> =
        sleepRepo.getSessionsForRange(date.minusDays(6), date).map { sessions ->
            sessions.map { session ->
                TimestampedReading(
                    timeLabel = session.date.format(HISTORY_DATE_FMT),
                    value = "%.1f".format(session.durationMinutes / 60.0),
                    unit = "h",
                )
            }
        }

    private fun buildSleepData(
        sleepWithStages: Pair<SleepSession, List<SleepStageEntity>>?,
        summary: DailySummary?,
        averages: SleepAverages,
        history: List<TimestampedReading>,
    ): SleepData? = sleepWithStages?.let { (session, stages) ->
        val totalMinutes = session.durationMinutes
        // Denominator for stage percentages is this night's own four stage fields, not the
        // session's separately-sourced duration — guarantees the four percentages can never
        // exceed 100% between them, regardless of any staleness in the session span they'd
        // otherwise be compared against.
        val stageTotalMinutes = listOfNotNull(
            summary?.sleepDeepMinutes, summary?.sleepLightMinutes,
            summary?.sleepRemMinutes, summary?.sleepAwakeMinutes,
        ).sum()
        SleepData(
            formattedDuration = formatDuration(totalMinutes),
            totalMinutes = totalMinutes,
            deepMinutes = summary?.sleepDeepMinutes,
            lightMinutes = summary?.sleepLightMinutes,
            remMinutes = summary?.sleepRemMinutes,
            awakeMinutes = summary?.sleepAwakeMinutes,
            deepPct = pctOfTotal(summary?.sleepDeepMinutes, stageTotalMinutes),
            lightPct = pctOfTotal(summary?.sleepLightMinutes, stageTotalMinutes),
            remPct = pctOfTotal(summary?.sleepRemMinutes, stageTotalMinutes),
            awakePct = pctOfTotal(summary?.sleepAwakeMinutes, stageTotalMinutes),
            sleepStartMs = session.sleepStartMs.toEpochMilli(),
            sleepEndMs = session.sleepEndMs.toEpochMilli(),
            onsetTimeLabel = session.sleepStartMs.toLocalTimeLabel(),
            wakeTimeLabel = session.sleepEndMs.toLocalTimeLabel(),
            hypnogramSegments = stages.sortedBy { it.startMs }.map { s ->
                HypnogramSegment(s.stage, s.startMs, s.endMs)
            },
            averages = averages,
            durationHistory = history,
        )
    } ?: summary?.sleepMinutes?.let { minutes ->
        val stageTotalMinutes = listOfNotNull(
            summary.sleepDeepMinutes, summary.sleepLightMinutes,
            summary.sleepRemMinutes, summary.sleepAwakeMinutes,
        ).sum()
        SleepData(
            formattedDuration = formatDuration(minutes),
            totalMinutes = minutes,
            deepMinutes = summary.sleepDeepMinutes,
            lightMinutes = summary.sleepLightMinutes,
            remMinutes = summary.sleepRemMinutes,
            awakeMinutes = summary.sleepAwakeMinutes,
            deepPct = pctOfTotal(summary.sleepDeepMinutes, stageTotalMinutes),
            lightPct = pctOfTotal(summary.sleepLightMinutes, stageTotalMinutes),
            remPct = pctOfTotal(summary.sleepRemMinutes, stageTotalMinutes),
            awakePct = pctOfTotal(summary.sleepAwakeMinutes, stageTotalMinutes),
            sleepStartMs = null,
            sleepEndMs = null,
            onsetTimeLabel = null,
            wakeTimeLabel = null,
            hypnogramSegments = emptyList(),
            averages = averages,
            durationHistory = history,
        )
    }
}

private fun pctOfTotal(stageMinutes: Int?, totalMinutes: Int): Double? =
    if (stageMinutes == null || totalMinutes <= 0) null else stageMinutes * 100.0 / totalMinutes

private fun formatDuration(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "${m}m"
        m == 0 -> "${h}h"
        else -> "${h}h ${m}m"
    }
}

private fun Instant.toLocalTimeLabel(): String =
    atZone(ZoneId.systemDefault()).format(TIME_FMT)
