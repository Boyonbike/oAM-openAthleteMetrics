package com.athletedata.app.ui.questions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.athletedata.app.data.model.DailyContext
import com.athletedata.app.data.repository.DailyContextRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

data class QuestionsUiState(
    val date: LocalDate = LocalDate.now(),
    val fatigue: Int? = null,
    val stress: Int? = null,
    val motivation: Int? = null,
    val sleepQuality: Int? = null,
    val performanceFeel: Int? = null,
    val isIll: Boolean = false,
    val illnessNotes: String = "",
    val habits: Map<String, Boolean> = DailyQuestionsViewModel.DEFAULT_HABITS,
    val isSaving: Boolean = false,
)

data class WeightUiState(
    val weightKg: String = "",
    val bodyFatPct: String = "",
    val notes: String = "",
    val isSaving: Boolean = false,
)

@HiltViewModel
class DailyQuestionsViewModel @Inject constructor(
    private val repo: DailyContextRepository,
) : ViewModel() {

    companion object {
        val HABIT_KEYS = listOf("alcohol", "meditation", "hydration", "sleep_routine")
        val HABIT_LABELS = mapOf(
            "alcohol" to "Alcohol",
            "meditation" to "Meditation",
            "hydration" to "Hydration",
            "sleep_routine" to "Sleep Routine",
        )
        val DEFAULT_HABITS: Map<String, Boolean> = HABIT_KEYS.associateWith { false }
    }

    private val today: LocalDate = LocalDate.now()

    private val _questionsState = MutableStateFlow(QuestionsUiState())
    val questionsState: StateFlow<QuestionsUiState> = _questionsState.asStateFlow()

    private val _weightState = MutableStateFlow(WeightUiState())
    val weightState: StateFlow<WeightUiState> = _weightState.asStateFlow()

    init {
        viewModelScope.launch {
            val existing = repo.getForDate(today).first()
            if (existing != null) populateFrom(existing)
        }
    }

    // ── Questions ─────────────────────────────────────────────────────────────

    fun onFatigueChange(value: Int?) = _questionsState.update { it.copy(fatigue = value) }
    fun onStressChange(value: Int?) = _questionsState.update { it.copy(stress = value) }
    fun onMotivationChange(value: Int?) = _questionsState.update { it.copy(motivation = value) }
    fun onSleepQualityChange(value: Int?) = _questionsState.update { it.copy(sleepQuality = value) }
    fun onPerformanceFeelChange(value: Int?) = _questionsState.update { it.copy(performanceFeel = value) }
    fun onIllToggle(isIll: Boolean) = _questionsState.update { it.copy(isIll = isIll) }
    fun onIllnessNotesChange(notes: String) = _questionsState.update { it.copy(illnessNotes = notes) }

    fun onHabitToggle(key: String) = _questionsState.update { state ->
        val updated = state.habits.toMutableMap()
        updated[key] = !(updated[key] ?: false)
        state.copy(habits = updated)
    }

    fun saveQuestions() {
        viewModelScope.launch {
            _questionsState.update { it.copy(isSaving = true) }
            repo.upsert(buildContext())
            _questionsState.update { it.copy(isSaving = false) }
        }
    }

    // ── Weight ────────────────────────────────────────────────────────────────

    fun onWeightKgChange(value: String) = _weightState.update { it.copy(weightKg = value) }
    fun onBodyFatPctChange(value: String) = _weightState.update { it.copy(bodyFatPct = value) }
    fun onWeightNotesChange(value: String) = _weightState.update { it.copy(notes = value) }

    fun saveWeight() {
        viewModelScope.launch {
            _weightState.update { it.copy(isSaving = true) }
            repo.upsert(buildContext())
            _weightState.update { it.copy(isSaving = false) }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun populateFrom(context: DailyContext) {
        _questionsState.update {
            it.copy(
                date = context.date,
                fatigue = context.fatigue,
                stress = context.stress,
                motivation = context.motivation,
                sleepQuality = context.sleepQuality,
                performanceFeel = context.performanceFeel,
                isIll = context.isIll,
                illnessNotes = context.illnessNotes ?: "",
                habits = parseHabits(context.habitsJson),
            )
        }
        _weightState.update {
            it.copy(
                weightKg = context.weightKg?.toString() ?: "",
                bodyFatPct = context.bodyFatPct?.toString() ?: "",
                notes = context.notes ?: "",
            )
        }
    }

    private fun buildContext(): DailyContext {
        val q = _questionsState.value
        val w = _weightState.value
        return DailyContext(
            date = today,
            fatigue = q.fatigue,
            stress = q.stress,
            motivation = q.motivation,
            sleepQuality = q.sleepQuality,
            performanceFeel = q.performanceFeel,
            isIll = q.isIll,
            illnessNotes = q.illnessNotes.takeIf { it.isNotBlank() },
            habitsJson = buildHabitsJson(q.habits),
            weightKg = w.weightKg.toDoubleOrNull(),
            bodyFatPct = w.bodyFatPct.toDoubleOrNull(),
            notes = w.notes.takeIf { it.isNotBlank() },
            updatedAt = Instant.now(),
        )
    }

    private fun parseHabits(json: String?): Map<String, Boolean> {
        if (json == null) return DEFAULT_HABITS
        return try {
            val obj = JSONObject(json)
            HABIT_KEYS.associateWith { key -> obj.optBoolean(key, false) }
        } catch (_: Exception) {
            DEFAULT_HABITS
        }
    }

    private fun buildHabitsJson(habits: Map<String, Boolean>): String {
        val obj = JSONObject()
        habits.forEach { (k, v) -> obj.put(k, v) }
        return obj.toString()
    }
}
