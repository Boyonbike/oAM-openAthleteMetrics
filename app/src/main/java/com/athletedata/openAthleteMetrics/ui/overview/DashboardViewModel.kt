package com.athletedata.openAthleteMetrics.ui.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.athletedata.openAthleteMetrics.data.model.DailyContext
import com.athletedata.openAthleteMetrics.data.model.UserCategory
import com.athletedata.openAthleteMetrics.data.model.WidgetLayout
import com.athletedata.openAthleteMetrics.data.model.WidgetSize
import com.athletedata.openAthleteMetrics.data.model.WidgetType
import com.athletedata.openAthleteMetrics.data.repository.ActivityRepository
import com.athletedata.openAthleteMetrics.data.repository.DailyContextRepository
import com.athletedata.openAthleteMetrics.data.repository.HrReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.QuestionRepository
import com.athletedata.openAthleteMetrics.data.repository.WidgetLayoutRepository
import com.athletedata.openAthleteMetrics.ui.dailydetail.DailyDetailSection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val widgetLayoutRepo: WidgetLayoutRepository,
    private val contextRepo: DailyContextRepository,
    private val questionRepo: QuestionRepository,
    private val activityRepo: ActivityRepository,
    private val hrReadingRepo: HrReadingRepository,
) : ViewModel() {

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val _navigationEvents = MutableSharedFlow<DashboardNavigationEvent>(extraBufferCapacity = 1)
    val navigationEvents: SharedFlow<DashboardNavigationEvent> = _navigationEvents.asSharedFlow()

    private val _date = MutableStateFlow(LocalDate.now())
    private val _isEditMode = MutableStateFlow(false)
    private val _optimisticLayout = MutableStateFlow<List<WidgetLayout>?>(null)

    private val _hasSeederData: StateFlow<Boolean> = _date
        .flatMapLatest { hrReadingRepo.hasSeederDataForDate(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val uiState: StateFlow<DashboardUiState> = combine(
        _date,
        widgetLayoutRepo.getLayout(),
        _isEditMode,
        _optimisticLayout,
        _hasSeederData,
    ) { date, dbLayout, editMode, optimistic, hasSeeder ->
        DashboardUiState(
            date = date,
            widgets = optimistic ?: dbLayout,
            isEditMode = editMode,
            hasSeederData = hasSeeder,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardUiState(),
    )

    fun setDate(date: LocalDate) {
        _date.value = date
    }

    fun toggleEditMode() {
        _isEditMode.update { !it }
    }

    fun addWidget(type: WidgetType, size: WidgetSize) {
        viewModelScope.launch {
            try {
                widgetLayoutRepo.addWidget(type, size)
            } catch (e: Exception) {
                Timber.e(e, "Failed to add widget")
                _errors.tryEmit("Failed to add widget")
            }
        }
    }

    fun removeWidget(id: Long) {
        viewModelScope.launch {
            try {
                widgetLayoutRepo.removeWidget(id)
            } catch (e: Exception) {
                Timber.e(e, "Failed to remove widget")
                _errors.tryEmit("Failed to remove widget")
            }
        }
    }

    fun onReorderLocal(fromIndex: Int, toIndex: Int) {
        val current = _optimisticLayout.value ?: uiState.value.widgets
        val mutable = current.toMutableList()
        val item = mutable.removeAt(fromIndex)
        mutable.add(toIndex, item)
        _optimisticLayout.value = mutable
    }

    fun onReorderPersist() {
        val ordered = _optimisticLayout.value ?: return
        viewModelScope.launch {
            try {
                widgetLayoutRepo.reorderWidgets(ordered.map { it.id })
                _optimisticLayout.value = null
            } catch (e: Exception) {
                Timber.e(e, "Failed to persist widget order")
                _optimisticLayout.value = null
                _errors.tryEmit("Failed to save widget order")
            }
        }
    }

    fun onWidgetTap(widget: WidgetLayout) {
        val date = _date.value
        viewModelScope.launch {
            val event = when (widget.type) {
                is WidgetType.Hr, WidgetType.Hrv, WidgetType.Rhr, WidgetType.Spo2 ->
                    DashboardNavigationEvent.OpenDailyDetail(
                        date,
                        DailyDetailSection.CARDIOVASCULAR,
                        widget.type.metricKey,
                    )
                is WidgetType.Sleep ->
                    DashboardNavigationEvent.OpenDailyDetail(date, DailyDetailSection.SLEEP, "SLEEP")
                is WidgetType.Steps ->
                    DashboardNavigationEvent.OpenDailyDetail(date, DailyDetailSection.ACTIVITY, "STEPS")
                is WidgetType.Activities ->
                    DashboardNavigationEvent.OpenDailyDetail(date, DailyDetailSection.ACTIVITIES)
                is WidgetType.Weight -> {
                    val context = contextRepo.getForDate(date).first()
                    if (context?.weightKg != null) {
                        DashboardNavigationEvent.OpenDailyDetail(date, DailyDetailSection.BODY)
                    } else {
                        DashboardNavigationEvent.OpenWeightSheet
                    }
                }
                is WidgetType.LifestyleQuestion, WidgetType.StarredLifestyleBar -> {
                    val responses = questionRepo.getResponsesForDate(date).first()
                    if (responses.isEmpty()) {
                        DashboardNavigationEvent.OpenQuestions(date)
                    } else {
                        DashboardNavigationEvent.OpenDailyDetail(date, DailyDetailSection.QUESTIONS)
                    }
                }
                is WidgetType.HabitQuestion, WidgetType.StarredHabitsBar -> {
                    val responses = questionRepo.getResponsesForDate(date).first()
                    val lifestyleIds = questionRepo.getLifestyleQuestionsOnce().map { it.id }.toSet()
                    val hasHabitResponse = responses.any { it.questionId !in lifestyleIds }
                    if (!hasHabitResponse) {
                        DashboardNavigationEvent.OpenHabitsTab(date)
                    } else {
                        DashboardNavigationEvent.OpenDailyDetail(date, DailyDetailSection.QUESTIONS)
                    }
                }
                else -> DashboardNavigationEvent.OpenDailyDetail(date, DailyDetailSection.CARDIOVASCULAR)
            }
            _navigationEvents.tryEmit(event)
        }
    }

    fun saveWeight(weightKg: Double?, bodyFatPct: Double?, notes: String?) {
        viewModelScope.launch {
            try {
                val date = _date.value
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

    fun contextForDate(date: LocalDate) = contextRepo.getForDate(date)

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
}
