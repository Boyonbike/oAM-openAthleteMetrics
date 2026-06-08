package com.athletedata.openAthleteMetrics.ui.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.athletedata.openAthleteMetrics.GlobalAppState
import com.athletedata.openAthleteMetrics.data.model.Activity
import com.athletedata.openAthleteMetrics.data.model.DailyContext
import com.athletedata.openAthleteMetrics.data.model.UserCategory
import com.athletedata.openAthleteMetrics.data.model.DailySummary
import com.athletedata.openAthleteMetrics.data.model.QuestionDefinition
import com.athletedata.openAthleteMetrics.data.model.QuestionType
import com.athletedata.openAthleteMetrics.data.repository.ActivityRepository
import com.athletedata.openAthleteMetrics.data.repository.DailyContextRepository
import com.athletedata.openAthleteMetrics.data.repository.DailySummaryRepository
import com.athletedata.openAthleteMetrics.data.repository.MetricRepository
import com.athletedata.openAthleteMetrics.data.repository.QuestionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

data class StarredLifestyleItem(
    val questionId: Long,
    val name: String,
    val value: String?,  // null = no response recorded for this date
)

data class StarredHabitItem(
    val questionId: Long,
    val name: String,
    val type: QuestionType,
    val value: String?,
)

data class OverviewUiState(
    val date: LocalDate = LocalDate.now(),
    val summaryForDate: DailySummary? = null,
    val contextForDate: DailyContext? = null,
    val yesterdaySummary: DailySummary? = null,
    val sparklineData: Map<String, List<Float>> = emptyMap(),
    val weightSparkline: List<Float> = emptyList(),
    val weightTrendLabel: String? = null,
    val hasSeederData: Boolean = false,
    val starredLifestyleItems: List<StarredLifestyleItem> = emptyList(),
    val starredHabitItems: List<StarredHabitItem> = emptyList(),
    val hasAnyQuestionsAnswered: Boolean = false,
    val isLoading: Boolean = true,
    val error: String? = null,
)

// Private bundle used to nest the 5-flow core combine before joining with question flows.
private data class CoreBundle(
    val today: DailySummary?,
    val yesterday: DailySummary?,
    val sparklineList: List<DailySummary>,
    val context: DailyContext?,
    val hasSeeder: Boolean,
)

private data class QuestionsBundle(
    val starredLifestyle: List<QuestionDefinition>,
    val starredHabits: List<QuestionDefinition>,
)

@HiltViewModel
class OverviewViewModel @Inject constructor(
    private val globalAppState: GlobalAppState,
    private val summaryRepo: DailySummaryRepository,
    private val contextRepo: DailyContextRepository,
    private val metricRepo: MetricRepository,
    private val questionRepo: QuestionRepository,
    private val activityRepo: ActivityRepository,
) : ViewModel() {

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val _dismissedForSession = MutableStateFlow(false)

    val uiState: StateFlow<OverviewUiState> = globalAppState.selectedDate
        .flatMapLatest { date ->
            val sparklineFrom = date.minusDays(6)

            val coreFlow = combine(
                summaryRepo.getSummaryForDate(date),
                summaryRepo.getSummaryForDate(date.minusDays(1)),
                summaryRepo.getSummariesForRange(sparklineFrom, date),
                contextRepo.getForDate(date),
                metricRepo.hasSeederDataForDate(date),
            ) { today, yesterday, sparklineList, context, hasSeeder ->
                CoreBundle(today, yesterday, sparklineList, context, hasSeeder)
            }

            val questionsFlow = combine(
                questionRepo.getStarredLifestyleQuestions(),
                questionRepo.getStarredHabitsQuestions(),
            ) { lifestyle, habits -> QuestionsBundle(lifestyle, habits) }

            combine(
                coreFlow,
                questionsFlow,
                questionRepo.getResponsesForDate(date),
                contextRepo.getForRange(sparklineFrom, date),
            ) { core, questions, responses, weightContexts ->
                val responseMap = responses.associateBy { it.questionId }
                OverviewUiState(
                    date = date,
                    summaryForDate = core.today,
                    contextForDate = core.context,
                    yesterdaySummary = core.yesterday,
                    sparklineData = buildSparklineData(core.sparklineList),
                    weightSparkline = buildWeightSparkline(weightContexts),
                    weightTrendLabel = computeWeightTrend(weightContexts, date),
                    hasSeederData = core.hasSeeder,
                    isLoading = false,
                    starredLifestyleItems = questions.starredLifestyle.map { q ->
                        StarredLifestyleItem(q.id, q.name, responseMap[q.id]?.value)
                    },
                    starredHabitItems = questions.starredHabits.map { q ->
                        StarredHabitItem(q.id, q.name, q.type, responseMap[q.id]?.value)
                    },
                    hasAnyQuestionsAnswered = responses.isNotEmpty(),
                )
            }.catch { e ->
                Timber.e(e, "Failed to load overview data for $date")
                emit(
                    OverviewUiState(
                        date = date,
                        isLoading = false,
                        error = e.message ?: "Database error",
                    )
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = OverviewUiState(),
        )

    val activities: StateFlow<List<Activity>> = globalAppState.selectedDate
        .flatMapLatest { date -> activityRepo.getActivitiesForDate(date) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val showCheckinBanner: StateFlow<Boolean> = combine(
        uiState,
        _dismissedForSession,
    ) { state, dismissed ->
        !dismissed && !state.isLoading &&
            state.date == LocalDate.now() &&
            !state.hasAnyQuestionsAnswered
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    fun setDate(date: LocalDate) {
        globalAppState.setDate(date)
    }

    fun dismissCheckinBanner() {
        _dismissedForSession.value = true
    }

    fun setActivityCategory(activityId: Long, category: UserCategory) {
        viewModelScope.launch {
            try {
                activityRepo.updateCategory(activityId, category)
            } catch (e: Exception) {
                Timber.e(e, "Failed to update activity category")
                _errors.tryEmit("Failed to save activity category")
            }
        }
    }

    fun saveWeight(weightKg: Double?, bodyFatPct: Double?, notes: String?) {
        viewModelScope.launch {
            try {
                val date = globalAppState.selectedDate.value
                val existing = contextRepo.getForDate(date).first()
                contextRepo.upsert(
                    (existing ?: DailyContext(date = date, updatedAt = Instant.now()))
                        .copy(
                            weightKg = weightKg,
                            bodyFatPct = bodyFatPct,
                            notes = notes?.takeIf { it.isNotBlank() },
                            updatedAt = Instant.now(),
                        ),
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to save weight")
                _errors.tryEmit("Failed to save weight")
            }
        }
    }

    private fun buildSparklineData(summaries: List<DailySummary>): Map<String, List<Float>> {
        val sorted = summaries.sortedBy { it.date }
        val restingHr = sorted.mapNotNull { it.restingHrBpm?.toFloat() }
        return mapOf(
            "HR" to sorted.mapNotNull { it.avgHrBpm?.toFloat() },
            "HRV" to sorted.mapNotNull { it.morningHrvMs?.toFloat() },
            "RHR" to restingHr,
            "SLEEP_STAGE" to sorted.mapNotNull { it.sleepMinutes?.toFloat() },
            "SPO2" to sorted.mapNotNull { it.avgSpo2Pct?.toFloat() },
            "STEPS" to sorted.mapNotNull { it.steps?.toFloat() },
        )
    }

    private fun buildWeightSparkline(contexts: List<DailyContext>): List<Float> =
        contexts.sortedBy { it.date }.mapNotNull { it.weightKg?.toFloat() }

    private fun computeWeightTrend(contexts: List<DailyContext>, date: LocalDate): String? {
        val today = contexts.find { it.date == date }?.weightKg ?: return null
        val yesterday = contexts.find { it.date == date.minusDays(1) }?.weightKg ?: return null
        if (yesterday == 0.0) return null
        val pct = (today - yesterday) / yesterday * 100.0
        val arrow = when {
            pct > 0.1  -> "↑"
            pct < -0.1 -> "↓"
            else       -> "→"
        }
        return "$arrow ${"%.1f".format(kotlin.math.abs(pct))}%"
    }

    private fun isCheckinIncomplete(context: DailyContext?): Boolean {
        if (context == null) return true
        return context.fatigue == null &&
            context.stress == null &&
            context.motivation == null &&
            context.sleepQuality == null &&
            context.performanceFeel == null &&
            (context.habitsJson == null || context.habitsJson == "{}")
    }

}
