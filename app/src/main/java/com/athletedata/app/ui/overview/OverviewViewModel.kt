package com.athletedata.app.ui.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.athletedata.app.GlobalAppState
import com.athletedata.app.data.model.DailyContext
import com.athletedata.app.data.model.DailySummary
import com.athletedata.app.data.repository.DailyContextRepository
import com.athletedata.app.data.repository.DailySummaryRepository
import com.athletedata.app.data.repository.MetricRepository
import com.athletedata.app.data.repository.QuestionRepository
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
import org.json.JSONObject
import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

data class StarredLifestyleItem(
    val questionId: Long,
    val name: String,
    val value: String?,  // null = no response recorded for this date
)

data class OverviewUiState(
    val date: LocalDate = LocalDate.now(),
    val summaryForDate: DailySummary? = null,
    val contextForDate: DailyContext? = null,
    val yesterdaySummary: DailySummary? = null,
    val sparklineData: Map<String, List<Float>> = emptyMap(),
    val habits: Map<String, Boolean> = emptyMap(),
    val hasSeederData: Boolean = false,
    val starredLifestyleItems: List<StarredLifestyleItem> = emptyList(),
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

@HiltViewModel
class OverviewViewModel @Inject constructor(
    private val globalAppState: GlobalAppState,
    private val summaryRepo: DailySummaryRepository,
    private val contextRepo: DailyContextRepository,
    private val metricRepo: MetricRepository,
    private val questionRepo: QuestionRepository,
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

            combine(
                coreFlow,
                questionRepo.getStarredLifestyleQuestions(),
                questionRepo.getResponsesForDate(date),
            ) { core, starredQuestions, responses ->
                val responseMap = responses.associateBy { it.questionId }
                OverviewUiState(
                    date = date,
                    summaryForDate = core.today,
                    contextForDate = core.context,
                    yesterdaySummary = core.yesterday,
                    sparklineData = buildSparklineData(core.sparklineList),
                    habits = parseHabits(core.context?.habitsJson),
                    hasSeederData = core.hasSeeder,
                    isLoading = false,
                    starredLifestyleItems = starredQuestions.map { q ->
                        StarredLifestyleItem(q.id, q.name, responseMap[q.id]?.value)
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

    fun toggleHabit(key: String) {
        viewModelScope.launch {
            try {
                val date = globalAppState.selectedDate.value
                val existing = contextRepo.getForDate(date).first()
                val habits = parseHabits(existing?.habitsJson).toMutableMap()
                habits[key] = !(habits[key] ?: false)
                contextRepo.upsert(
                    (existing ?: DailyContext(date = date, updatedAt = Instant.now()))
                        .copy(habitsJson = buildHabitsJson(habits), updatedAt = Instant.now()),
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to toggle habit")
                _errors.tryEmit("Failed to save habit")
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
            "HR" to restingHr,
            "HRV" to sorted.mapNotNull { it.morningHrvMs?.toFloat() },
            "RHR" to restingHr,
            "SLEEP_STAGE" to sorted.mapNotNull { it.sleepMinutes?.toFloat() },
            "SPO2" to sorted.mapNotNull { it.avgSpo2Pct?.toFloat() },
            "STEPS" to sorted.mapNotNull { it.steps?.toFloat() },
        )
    }

    private fun parseHabits(json: String?): Map<String, Boolean> {
        if (json == null) return HABIT_KEYS.associateWith { false }
        return try {
            val obj = JSONObject(json)
            HABIT_KEYS.associateWith { key -> obj.optBoolean(key, false) }
        } catch (_: Exception) {
            HABIT_KEYS.associateWith { false }
        }
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

    private fun buildHabitsJson(habits: Map<String, Boolean>): String {
        val obj = JSONObject()
        habits.forEach { (k, v) -> obj.put(k, v) }
        return obj.toString()
    }

    companion object {
        val HABIT_KEYS = listOf("alcohol", "meditation", "hydration", "sleep_routine")
        val HABIT_LABELS = mapOf(
            "alcohol" to "Alcohol",
            "meditation" to "Meditation",
            "hydration" to "Hydration",
            "sleep_routine" to "Sleep Routine",
        )
    }
}
