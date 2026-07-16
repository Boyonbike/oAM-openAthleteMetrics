package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.model.Activity
import com.athletedata.openAthleteMetrics.data.model.DailyContext
import com.athletedata.openAthleteMetrics.data.model.DailySummary
import com.athletedata.openAthleteMetrics.data.model.DataSourceKey
import com.athletedata.openAthleteMetrics.data.model.toLabel
import java.time.LocalDate

/**
 * Everything [resolveDataSource] needs for one date, pre-fetched by the caller. Keeping
 * this as a plain data bundle (rather than having the dispatcher reach into repositories
 * itself) keeps [resolveDataSource] synchronous and pure, so it can be unit-tested with
 * hand-built fixtures and reused later by a file-import validator that won't have live
 * suspend/Flow repository access.
 */
data class WidgetDataContext(
    val date: LocalDate,
    val summary: DailySummary?,
    val context: DailyContext?,
    val primaryActivity: Activity?,
    val activityCountToday: Int,
)

sealed class ResolvedValue {
    data class Numeric(val value: Double) : ResolvedValue()
    data class IntValue(val value: Int) : ResolvedValue()
    data class BooleanValue(val value: Boolean) : ResolvedValue()
    data class Text(val value: String) : ResolvedValue()

    /** The source row existed but this field was null, or the source row itself was null. */
    object Unavailable : ResolvedValue()

    /** Native-only keys ([DataSourceKey.STARRED_LIFESTYLE_QUESTIONS]/[DataSourceKey.STARRED_CUSTOM_QUESTIONS]) — never resolvable through this dispatcher. */
    object NotImportable : ResolvedValue()
}

/**
 * The single dispatcher every metric-bound widget resolves against — the only place that
 * translates a [DataSourceKey] into an actual field read. Used today by the generic
 * in-app renderer; a future file-import validator reuses the same function, rejecting
 * [ResolvedValue.NotImportable] keys rather than needing separate dispatch logic.
 *
 * [DataSourceKey.HRV] is deliberately unresolved here — it composes three data sources
 * (sleep session, baseline range, session-scoped HRV readings), not a single field lookup,
 * so it always resolves via its own bespoke pipeline and never reaches this function.
 */
fun resolveDataSource(key: DataSourceKey, ctx: WidgetDataContext): ResolvedValue = when (key) {
    DataSourceKey.HR -> ctx.summary?.avgHrBpm?.let(ResolvedValue::Numeric) ?: ResolvedValue.Unavailable
    DataSourceKey.RHR -> ctx.summary?.restingHrBpm?.let(ResolvedValue::Numeric) ?: ResolvedValue.Unavailable
    DataSourceKey.SLEEP -> ctx.summary?.sleepMinutes?.let(ResolvedValue::IntValue) ?: ResolvedValue.Unavailable
    DataSourceKey.SPO2 -> ctx.summary?.avgSpo2Pct?.let(ResolvedValue::Numeric) ?: ResolvedValue.Unavailable
    DataSourceKey.STEPS -> ctx.summary?.steps?.let(ResolvedValue::IntValue) ?: ResolvedValue.Unavailable
    DataSourceKey.HRV -> ResolvedValue.Unavailable
    DataSourceKey.DAILY_AVG_HRV_MS -> ctx.summary?.avgHrvMs?.let(ResolvedValue::Numeric) ?: ResolvedValue.Unavailable
    DataSourceKey.SKIN_TEMP_AVG_C -> ctx.summary?.skinTempAvgC?.let(ResolvedValue::Numeric) ?: ResolvedValue.Unavailable
    DataSourceKey.SKIN_TEMP_MIN_C -> ctx.summary?.skinTempMinC?.let(ResolvedValue::Numeric) ?: ResolvedValue.Unavailable
    DataSourceKey.SKIN_TEMP_MAX_C -> ctx.summary?.skinTempMaxC?.let(ResolvedValue::Numeric) ?: ResolvedValue.Unavailable
    DataSourceKey.RESPIRATION_AVG -> ctx.summary?.respirationAvg?.let(ResolvedValue::Numeric) ?: ResolvedValue.Unavailable
    DataSourceKey.HRV_MIN_MS -> ctx.summary?.hrvMinMs?.let(ResolvedValue::Numeric) ?: ResolvedValue.Unavailable
    DataSourceKey.HRV_MAX_MS -> ctx.summary?.hrvMaxMs?.let(ResolvedValue::Numeric) ?: ResolvedValue.Unavailable
    DataSourceKey.SPO2_MIN_PCT -> ctx.summary?.spo2MinPct?.let(ResolvedValue::Numeric) ?: ResolvedValue.Unavailable
    DataSourceKey.SPO2_MAX_PCT -> ctx.summary?.spo2MaxPct?.let(ResolvedValue::Numeric) ?: ResolvedValue.Unavailable
    DataSourceKey.STEPS_ACTIVE_MINUTES -> ctx.summary?.stepsActiveMinutes?.let(ResolvedValue::IntValue) ?: ResolvedValue.Unavailable
    DataSourceKey.SUMMARY_TOTAL_CALORIES -> ctx.summary?.totalCalories?.let(ResolvedValue::Numeric) ?: ResolvedValue.Unavailable
    DataSourceKey.SUMMARY_ACTIVE_CALORIES -> ctx.summary?.activeCalories?.let(ResolvedValue::Numeric) ?: ResolvedValue.Unavailable
    DataSourceKey.AVG_SYSTOLIC_MMHG -> ctx.summary?.avgSystolicMmHg?.let(ResolvedValue::Numeric) ?: ResolvedValue.Unavailable
    DataSourceKey.AVG_DIASTOLIC_MMHG -> ctx.summary?.avgDiastolicMmHg?.let(ResolvedValue::Numeric) ?: ResolvedValue.Unavailable
    DataSourceKey.AVG_GLUCOSE_MMOLL -> ctx.summary?.avgGlucoseMmolL?.let(ResolvedValue::Numeric) ?: ResolvedValue.Unavailable
    DataSourceKey.SLEEP_DEEP_MINUTES -> ctx.summary?.sleepDeepMinutes?.let(ResolvedValue::IntValue) ?: ResolvedValue.Unavailable
    DataSourceKey.SLEEP_LIGHT_MINUTES -> ctx.summary?.sleepLightMinutes?.let(ResolvedValue::IntValue) ?: ResolvedValue.Unavailable
    DataSourceKey.SLEEP_REM_MINUTES -> ctx.summary?.sleepRemMinutes?.let(ResolvedValue::IntValue) ?: ResolvedValue.Unavailable
    DataSourceKey.SLEEP_AWAKE_MINUTES -> ctx.summary?.sleepAwakeMinutes?.let(ResolvedValue::IntValue) ?: ResolvedValue.Unavailable

    DataSourceKey.WEIGHT_KG -> ctx.context?.weightKg?.let(ResolvedValue::Numeric) ?: ResolvedValue.Unavailable
    DataSourceKey.BODY_FAT_PCT -> ctx.context?.bodyFatPct?.let(ResolvedValue::Numeric) ?: ResolvedValue.Unavailable
    DataSourceKey.NOTES -> ctx.context?.notes?.let(ResolvedValue::Text) ?: ResolvedValue.Unavailable
    DataSourceKey.FATIGUE -> ctx.context?.fatigue?.let(ResolvedValue::IntValue) ?: ResolvedValue.Unavailable
    DataSourceKey.STRESS -> ctx.context?.stress?.let(ResolvedValue::IntValue) ?: ResolvedValue.Unavailable
    DataSourceKey.MOTIVATION -> ctx.context?.motivation?.let(ResolvedValue::IntValue) ?: ResolvedValue.Unavailable
    DataSourceKey.SLEEP_QUALITY -> ctx.context?.sleepQuality?.let(ResolvedValue::IntValue) ?: ResolvedValue.Unavailable
    DataSourceKey.PERFORMANCE_FEEL -> ctx.context?.performanceFeel?.let(ResolvedValue::IntValue) ?: ResolvedValue.Unavailable
    DataSourceKey.IS_ILL -> ctx.context?.isIll?.let(ResolvedValue::BooleanValue) ?: ResolvedValue.Unavailable
    DataSourceKey.ILLNESS_NOTES -> ctx.context?.illnessNotes?.let(ResolvedValue::Text) ?: ResolvedValue.Unavailable

    DataSourceKey.ACTIVITY_CATEGORY_LABEL -> ctx.primaryActivity?.let {
        ResolvedValue.Text(it.userCategory?.toLabel() ?: it.deviceName)
    } ?: ResolvedValue.Unavailable
    DataSourceKey.ACTIVITY_DURATION_MIN -> ctx.primaryActivity?.durationMinutes?.let(ResolvedValue::IntValue) ?: ResolvedValue.Unavailable
    DataSourceKey.ACTIVITY_COUNT_TODAY -> ResolvedValue.IntValue(ctx.activityCountToday)
    DataSourceKey.ACTIVITY_AVG_HR_BPM -> ctx.primaryActivity?.avgHrBpm?.let(ResolvedValue::Numeric) ?: ResolvedValue.Unavailable
    DataSourceKey.ACTIVITY_MAX_HR_BPM -> ctx.primaryActivity?.maxHrBpm?.let(ResolvedValue::Numeric) ?: ResolvedValue.Unavailable
    DataSourceKey.ACTIVITY_MIN_HR_BPM -> ctx.primaryActivity?.minHrBpm?.let(ResolvedValue::Numeric) ?: ResolvedValue.Unavailable
    DataSourceKey.ACTIVITY_CALORIES -> ctx.primaryActivity?.calories?.let(ResolvedValue::Numeric) ?: ResolvedValue.Unavailable
    DataSourceKey.ACTIVITY_ACTIVE_CALORIES -> ctx.primaryActivity?.activeCalories?.let(ResolvedValue::Numeric) ?: ResolvedValue.Unavailable
    DataSourceKey.ACTIVITY_DISTANCE_M -> ctx.primaryActivity?.distanceMeters?.let(ResolvedValue::Numeric) ?: ResolvedValue.Unavailable
    DataSourceKey.ACTIVITY_STEPS -> ctx.primaryActivity?.steps?.let(ResolvedValue::IntValue) ?: ResolvedValue.Unavailable

    DataSourceKey.STARRED_LIFESTYLE_QUESTIONS,
    DataSourceKey.STARRED_CUSTOM_QUESTIONS,
    -> ResolvedValue.NotImportable
}
