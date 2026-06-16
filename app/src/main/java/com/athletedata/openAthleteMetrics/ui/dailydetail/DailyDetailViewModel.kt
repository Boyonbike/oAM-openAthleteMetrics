package com.athletedata.openAthleteMetrics.ui.dailydetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.athletedata.openAthleteMetrics.GlobalAppState
import com.athletedata.openAthleteMetrics.data.model.Activity
import com.athletedata.openAthleteMetrics.data.model.DailySummary
import com.athletedata.openAthleteMetrics.data.model.QuestionCategory
import com.athletedata.openAthleteMetrics.data.model.QuestionDefinition
import com.athletedata.openAthleteMetrics.data.model.QuestionResponse
import com.athletedata.openAthleteMetrics.data.model.QuestionType
import com.athletedata.openAthleteMetrics.data.model.UserCategory
import com.athletedata.openAthleteMetrics.data.repository.ActivityRepository
import com.athletedata.openAthleteMetrics.data.repository.DailyContextRepository
import com.athletedata.openAthleteMetrics.data.repository.DailySummaryRepository
import com.athletedata.openAthleteMetrics.data.repository.QuestionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DailyDetailViewModel @Inject constructor(
    private val globalAppState: GlobalAppState,
    private val summaryRepo: DailySummaryRepository,
    private val contextRepo: DailyContextRepository,
    private val activityRepo: ActivityRepository,
    private val questionRepo: QuestionRepository,
) : ViewModel() {

    private data class QuestionsBundle(
        val responses: List<QuestionResponse>,
        val definitions: List<QuestionDefinition>,
    )

    val uiState: StateFlow<DailyDetailUiState> = globalAppState.selectedDate
        .flatMapLatest { date ->
            val questionsFlow = combine(
                questionRepo.getResponsesForDate(date),
                questionRepo.getLifestyleQuestions(),
                questionRepo.getCustomQuestions(),
            ) { responses, lifestyle, custom ->
                QuestionsBundle(responses, lifestyle + custom)
            }

            combine(
                summaryRepo.getSummaryForDate(date),
                contextRepo.getForDate(date),
                activityRepo.getActivitiesForDate(date),
                questionsFlow,
            ) { summary, context, activities, questions ->
                DailyDetailUiState.Success(
                    date = date,
                    summary = summary,
                    context = context,
                    activities = activities.map { it.toUiItem() },
                    questionGroups = buildQuestionGroups(questions.responses, questions.definitions),
                    sleep = buildSleepUiItem(summary),
                ) as DailyDetailUiState
            }.catch { e ->
                emit(DailyDetailUiState.Error(e.message ?: "Unknown error"))
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DailyDetailUiState.Loading,
        )

    fun updateActivityCategory(id: Long, category: UserCategory, notes: String?) {
        viewModelScope.launch { activityRepo.updateCategoryAndNotes(id, category, notes) }
    }

    private fun Activity.toUiItem() = ActivityUiItem(
        id = id,
        deviceName = deviceName,
        userCategory = userCategory,
        formattedDuration = formatDuration(durationMinutes),
        avgHrBpm = avgHrBpm,
        notes = notes,
    )

    private fun buildSleepUiItem(summary: DailySummary?): SleepUiItem? {
        val minutes = summary?.sleepMinutes ?: return null
        val stages = if (summary.sleepDeepMinutes != null || summary.sleepLightMinutes != null || summary.sleepRemMinutes != null) {
            SleepStages(
                deepMinutes  = summary.sleepDeepMinutes  ?: 0,
                lightMinutes = summary.sleepLightMinutes ?: 0,
                remMinutes   = summary.sleepRemMinutes   ?: 0,
            )
        } else null
        return SleepUiItem(
            formattedDuration = formatDuration(minutes),
            stages = stages,
        )
    }

    private fun buildQuestionGroups(
        responses: List<QuestionResponse>,
        definitions: List<QuestionDefinition>,
    ): List<QuestionGroup> {
        val defMap = definitions.associateBy { it.id }
        data class Row(val name: String, val category: QuestionCategory, val value: String)
        val rows = responses.mapNotNull { r ->
            val def = defMap[r.questionId] ?: return@mapNotNull null
            Row(def.name, def.category, formatResponseValue(r.value, def.type))
        }
        return rows
            .groupBy { it.category }
            .entries
            .sortedBy { (cat, _) -> if (cat == QuestionCategory.LIFESTYLE) 0 else 1 }
            .map { (cat, grouped) ->
                QuestionGroup(
                    category = cat,
                    items = grouped.map { QuestionItem(it.name, it.value) },
                )
            }
    }

    private fun formatResponseValue(value: String, type: QuestionType): String = when (type) {
        QuestionType.BOOLEAN -> if (value == "1") "Yes" else "No"
        QuestionType.SCALE   -> value
        QuestionType.TEXT    -> value
    }

}

private fun formatDuration(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "${m}m"
        m == 0 -> "${h}h"
        else   -> "${h}h ${m}m"
    }
}
