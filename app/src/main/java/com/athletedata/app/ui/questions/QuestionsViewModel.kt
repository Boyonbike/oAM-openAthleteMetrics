package com.athletedata.app.ui.questions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.athletedata.app.GlobalAppState
import com.athletedata.app.data.model.QuestionCategory
import com.athletedata.app.data.model.QuestionDefinition
import com.athletedata.app.data.model.QuestionResponse
import com.athletedata.app.data.model.QuestionType
import com.athletedata.app.data.repository.QuestionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDate
import javax.inject.Inject

data class QuestionItem(
    val definition: QuestionDefinition,
    val currentValue: String?,
)

@HiltViewModel
class QuestionsViewModel @Inject constructor(
    private val globalAppState: GlobalAppState,
    private val repo: QuestionRepository,
) : ViewModel() {

    private val _editMode = MutableStateFlow(false)
    val editMode: StateFlow<Boolean> = _editMode.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    val date: StateFlow<LocalDate> = globalAppState.selectedDate
        .stateIn(viewModelScope, SharingStarted.Eagerly, LocalDate.now())

    private val sharedResponses = globalAppState.selectedDate
        .flatMapLatest { repo.getResponsesForDate(it) }
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    val lifestyleItems: StateFlow<List<QuestionItem>> = combine(
        repo.getLifestyleQuestions(),
        sharedResponses,
    ) { defs, responses ->
        val map = responses.associateBy { it.questionId }
        defs.map { QuestionItem(it, map[it.id]?.value) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val customItems: StateFlow<List<QuestionItem>> = combine(
        repo.getCustomQuestions(),
        sharedResponses,
    ) { defs, responses ->
        val map = responses.associateBy { it.questionId }
        defs.map { QuestionItem(it, map[it.id]?.value) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun saveResponse(questionId: Long, value: String) {
        viewModelScope.launch {
            try {
                repo.upsertResponse(questionId, date.value, value)
            } catch (e: Exception) {
                Timber.e(e, "Failed to save response for question $questionId")
                _errors.tryEmit("Failed to save")
            }
        }
    }

    fun clearResponse(questionId: Long) {
        viewModelScope.launch {
            try {
                repo.clearResponse(questionId, date.value)
            } catch (e: Exception) {
                Timber.e(e, "Failed to clear response for question $questionId")
            }
        }
    }

    fun toggleVisibility(questionId: Long) {
        viewModelScope.launch {
            try {
                val item = (lifestyleItems.value + customItems.value)
                    .find { it.definition.id == questionId } ?: return@launch
                repo.toggleVisibility(questionId, item.definition.isVisible)
            } catch (e: Exception) {
                Timber.e(e, "Failed to toggle visibility for question $questionId")
                _errors.tryEmit("Failed to update")
            }
        }
    }

    fun reorder(category: QuestionCategory, fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            try {
                val items = if (category == QuestionCategory.LIFESTYLE) lifestyleItems.value
                            else customItems.value
                if (fromIndex !in items.indices || toIndex !in items.indices) return@launch
                val reordered = items.toMutableList()
                val moved = reordered.removeAt(fromIndex)
                reordered.add(toIndex, moved)
                repo.reorderQuestions(reordered.mapIndexed { i, item -> item.definition.id to (i + 1) })
            } catch (e: Exception) {
                Timber.e(e, "Failed to reorder questions")
                _errors.tryEmit("Failed to reorder")
            }
        }
    }

    fun deleteCustomQuestion(questionId: Long) {
        viewModelScope.launch {
            try {
                repo.deleteCustomQuestion(questionId)
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete question $questionId")
                _errors.tryEmit("Failed to delete")
            }
        }
    }

    fun editCustomQuestion(questionId: Long, name: String, type: QuestionType) {
        viewModelScope.launch {
            try {
                repo.updateCustomQuestion(questionId, name, type)
            } catch (e: Exception) {
                Timber.e(e, "Failed to edit question $questionId")
                _errors.tryEmit("Failed to save changes")
            }
        }
    }

    fun toggleStar(questionId: Long) {
        viewModelScope.launch {
            try {
                val item = lifestyleItems.value.find { it.definition.id == questionId } ?: return@launch
                if (!item.definition.isStarred) {
                    // Trying to star — blocked if hidden or already at the 5-star limit.
                    if (!item.definition.isVisible) return@launch
                    val starredCount = lifestyleItems.value.count { it.definition.isStarred }
                    if (starredCount >= 5) {
                        _errors.tryEmit("Only 5 questions can be shown in the dashboard bar")
                        return@launch
                    }
                }
                repo.toggleStar(questionId, item.definition.isStarred)
            } catch (e: Exception) {
                Timber.e(e, "Failed to toggle star for question $questionId")
                _errors.tryEmit("Failed to update")
            }
        }
    }

    fun addCustomQuestion(name: String, type: QuestionType) {
        viewModelScope.launch {
            try {
                repo.addCustomQuestion(name, type)
            } catch (e: Exception) {
                Timber.e(e, "Failed to add custom question")
                _errors.tryEmit("Failed to add question")
            }
        }
    }

    fun enterEditMode() {
        _editMode.value = true
    }

    fun exitEditMode() {
        _editMode.value = false
    }
}
