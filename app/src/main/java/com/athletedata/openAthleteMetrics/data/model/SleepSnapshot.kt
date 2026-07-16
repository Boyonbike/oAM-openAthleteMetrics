package com.athletedata.openAthleteMetrics.data.model

/**
 * Fully-assembled sleep data for one night, shared by the Daily Detail sleep card, the
 * in-app dashboard's sleep widgets, and the Glance home-screen sleep widgets — see
 * [com.athletedata.openAthleteMetrics.data.repository.SleepDetailProvider], the single
 * assembler all three consumers call instead of duplicating the query/combine logic.
 */
data class SleepData(
    val formattedDuration: String,
    val totalMinutes: Int,
    val deepMinutes: Int?,
    val lightMinutes: Int?,
    val remMinutes: Int?,
    val awakeMinutes: Int?,
    val deepPct: Double?,
    val lightPct: Double?,
    val remPct: Double?,
    val awakePct: Double?,
    val sleepStartMs: Long?,
    val sleepEndMs: Long?,
    val onsetTimeLabel: String?,
    val wakeTimeLabel: String?,
    val hypnogramSegments: List<HypnogramSegment>,
    val averages: SleepAverages,
    val durationHistory: List<TimestampedReading>,
)

data class HypnogramSegment(
    val stage: SleepStage,
    val startMs: Long,
    val endMs: Long,
)

data class TimestampedReading(
    val timeLabel: String,
    val value: String,
    val unit: String,
)
