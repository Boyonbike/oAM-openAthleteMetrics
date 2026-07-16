package com.athletedata.openAthleteMetrics.ui.dailydetail

import com.athletedata.openAthleteMetrics.data.model.BaselineRange
import com.athletedata.openAthleteMetrics.data.model.QuestionCategory
import com.athletedata.openAthleteMetrics.data.model.SleepData
import com.athletedata.openAthleteMetrics.data.model.TimestampedReading
import com.athletedata.openAthleteMetrics.data.model.UserCategory
import java.time.LocalDate

sealed class DailyDetailUiState {
    object Loading : DailyDetailUiState()
    data class Error(val message: String) : DailyDetailUiState()
    data class Success(
        val date: LocalDate,
        val tileOrder: List<TileConfig>,
        val cardiovascular: CardiovascularData?,
        val sleep: SleepData?,
        val activity: ActivityData?,
        val body: BodyData?,
        val questionGroups: List<QuestionGroup>,
        val isIll: Boolean,
        val illnessNotes: String?,
        val habitsJson: String?,
        val contextScores: ContextScores?,
        val activities: List<ActivityUiItem>,
        val rawReadings: RawReadingsForDay,
        val hrv: HrvSectionState,
    ) : DailyDetailUiState()
}

sealed class HrvSectionState {
    data class HasData(
        val headlineMs: Double,
        val baseline: BaselineRange?,
        val rawReadings: List<TimestampedReading>,
    ) : HrvSectionState()

    data class InsufficientData(
        val rawReadings: List<TimestampedReading>,
    ) : HrvSectionState()

    object NoSleepSession : HrvSectionState()
}

data class TileConfig(val id: String, val isVisible: Boolean, val sortOrder: Int)

val DEFAULT_TILE_ORDER = listOf(
    TileConfig("cardiovascular", true, 0),
    TileConfig("sleep", true, 1),
    TileConfig("activity", true, 2),
    TileConfig("body", true, 3),
    TileConfig("questions", true, 4),
    TileConfig("activities", true, 5),
)

data class CardiovascularData(
    val avgHrBpm: Double?,
    val restingHrBpm: Double?,
    val overnightHrvMs: Double?,
    val avgSpo2Pct: Double?,
    val spo2MinPct: Double?,
    val spo2MaxPct: Double?,
)

data class ActivityData(
    val steps: Int?,
    val activeCalories: Double?,
    val exerciseMinutes: Int?,
)

data class BodyData(
    val weightKg: Double?,
    val bodyFatPct: Double?,
    val respirationAvg: Double?,
    val totalCalories: Double?,
)

data class ContextScores(
    val fatigue: Int?,
    val stress: Int?,
    val motivation: Int?,
    val sleepQuality: Int?,
    val performanceFeel: Int?,
)

data class RawReadingsForDay(
    val hrReadings: List<TimestampedReading> = emptyList(),
    val spo2Readings: List<TimestampedReading> = emptyList(),
    val respirationReadings: List<TimestampedReading> = emptyList(),
    val stepsReadings: List<TimestampedReading> = emptyList(),
    val activeCalorieReadings: List<TimestampedReading> = emptyList(),
    val totalCalorieReadings: List<TimestampedReading> = emptyList(),
)

data class ActivityUiItem(
    val id: Long,
    val deviceName: String,
    val userCategory: UserCategory?,
    val formattedDuration: String,
    val avgHrBpm: Double?,
    val notes: String?,
)

data class QuestionGroup(
    val category: QuestionCategory,
    val items: List<QuestionItem>,
)

data class QuestionItem(
    val name: String,
    val value: String,
)
