package com.athletedata.openAthleteMetrics.data.model

import java.time.LocalTime

/**
 * Plain historical averages for the Sleep card, computed by
 * [com.athletedata.openAthleteMetrics.data.repository.SleepAverageCalculator]. Each field is
 * independently nullable — insufficient history for one value (e.g. too few nights with an
 * onset time) must not blank out fields that do have enough history.
 */
data class SleepAverages(
    val avgTotalMinutes: Int?,
    val avgOnsetTime: LocalTime?,
    val avgWakeTime: LocalTime?,
    val avgDeepMinutes: Int?,
    val avgDeepPct: Double?,
    val avgLightMinutes: Int?,
    val avgLightPct: Double?,
    val avgRemMinutes: Int?,
    val avgRemPct: Double?,
    val avgAwakeMinutes: Int?,
    val avgAwakePct: Double?,
)
