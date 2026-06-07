package com.athletedata.app.ui.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.athletedata.app.data.model.DailyContext
import com.athletedata.app.data.model.DailySummary
import com.athletedata.app.data.repository.DailyContextRepository
import com.athletedata.app.data.repository.DailySummaryRepository
import com.athletedata.app.data.repository.MetricRepository
import com.athletedata.app.ui.components.ScreenState
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

data class OverviewUiState(
    val selectedDate: LocalDate = LocalDate.now(),
    val summaryState: ScreenState<DailySummary> = ScreenState.Loading,
    val contextState: ScreenState<DailyContext> = ScreenState.Loading,
    val habits: Map<String, Boolean> = emptyMap(),
    val hasSeederData: Boolean = false,
)

@HiltViewModel
class OverviewViewModel @Inject constructor(
    private val summaryRepo: DailySummaryRepository,
    private val contextRepo: DailyContextRepository,
    private val metricRepo: MetricRepository,
) : ViewModel() {

    private val _selectedDate = MutableStateFlow(LocalDate.now())

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    val uiState: StateFlow<OverviewUiState> = _selectedDate
        .flatMapLatest { date ->
            combine(
                summaryRepo.getSummaryForDate(date),
                contextRepo.getForDate(date),
                metricRepo.hasSeederDataForDate(date),
            ) { summary, context, hasSeeder ->
                OverviewUiState(
                    selectedDate = date,
                    summaryState = if (summary != null) ScreenState.Success(summary) else ScreenState.Empty,
                    contextState = if (context != null) ScreenState.Success(context) else ScreenState.Empty,
                    habits = parseHabits(context?.habitsJson),
                    hasSeederData = hasSeeder,
                )
            }.catch { e ->
                Timber.e(e, "Failed to load overview data for $date")
                emit(
                    OverviewUiState(
                        selectedDate = date,
                        summaryState = ScreenState.Error(e.message ?: "Database error"),
                        contextState = ScreenState.Error(e.message ?: "Database error"),
                    )
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = OverviewUiState(),
        )

    fun onDateChange(date: LocalDate) {
        _selectedDate.value = date
    }

    fun onHabitToggle(key: String) {
        viewModelScope.launch {
            try {
                val date = _selectedDate.value
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
                val date = _selectedDate.value
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

    private fun parseHabits(json: String?): Map<String, Boolean> {
        if (json == null) return HABIT_KEYS.associateWith { false }
        return try {
            val obj = JSONObject(json)
            HABIT_KEYS.associateWith { key -> obj.optBoolean(key, false) }
        } catch (_: Exception) {
            HABIT_KEYS.associateWith { false }
        }
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
