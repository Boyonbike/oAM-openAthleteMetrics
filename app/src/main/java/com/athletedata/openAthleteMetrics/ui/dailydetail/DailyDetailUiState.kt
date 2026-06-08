package com.athletedata.openAthleteMetrics.ui.dailydetail

import com.athletedata.openAthleteMetrics.data.model.DailyContext
import com.athletedata.openAthleteMetrics.data.model.DailySummary
import com.athletedata.openAthleteMetrics.data.model.QuestionCategory
import com.athletedata.openAthleteMetrics.data.model.UserCategory
import java.time.LocalDate

sealed class DailyDetailUiState {
    object Loading : DailyDetailUiState()
    data class Success(
        val date: LocalDate,
        val summary: DailySummary?,
        val context: DailyContext?,
        val activities: List<ActivityUiItem>,
        val questionGroups: List<QuestionGroup>,
        val sleep: SleepUiItem?,
    ) : DailyDetailUiState()
    data class Error(val message: String) : DailyDetailUiState()
}

data class ActivityUiItem(
    val id: Long,
    val deviceName: String,
    val userCategory: UserCategory?,
    val formattedDuration: String,
    val avgHrBpm: Double?,
    val notes: String?,
)

data class SleepUiItem(
    val formattedDuration: String,
    val stages: SleepStages?,
)

data class SleepStages(
    val deepMinutes: Int,
    val lightMinutes: Int,
    val remMinutes: Int,
)

data class QuestionGroup(
    val category: QuestionCategory,
    val items: List<QuestionItem>,
)

data class QuestionItem(
    val name: String,
    val value: String,
)
