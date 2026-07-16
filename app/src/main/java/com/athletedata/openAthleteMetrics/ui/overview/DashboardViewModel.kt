package com.athletedata.openAthleteMetrics.ui.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.athletedata.openAthleteMetrics.data.model.DailyContext
import com.athletedata.openAthleteMetrics.data.model.UserCategory
import com.athletedata.openAthleteMetrics.data.model.WidgetDefinition
import com.athletedata.openAthleteMetrics.data.model.WidgetTemplateId
import com.athletedata.openAthleteMetrics.data.repository.ActivityRepository
import com.athletedata.openAthleteMetrics.data.repository.DailyContextRepository
import com.athletedata.openAthleteMetrics.data.repository.HrReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.WidgetLayoutRepository
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
    private val activityRepo: ActivityRepository,
    private val hrReadingRepo: HrReadingRepository,
    private val widgetTapResolver: WidgetTapDestinationResolver,
) : ViewModel() {

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val _navigationEvents = MutableSharedFlow<DashboardNavigationEvent>(extraBufferCapacity = 1)
    val navigationEvents: SharedFlow<DashboardNavigationEvent> = _navigationEvents.asSharedFlow()

    private val _date = MutableStateFlow(LocalDate.now())
    private val _isEditMode = MutableStateFlow(false)

    private val _hasSeederData: StateFlow<Boolean> = _date
        .flatMapLatest { hrReadingRepo.hasSeederDataForDate(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val uiState: StateFlow<DashboardUiState> = combine(
        _date,
        widgetLayoutRepo.getLayout(),
        _isEditMode,
        _hasSeederData,
    ) { date, dbLayout, editMode, hasSeeder ->
        DashboardUiState(
            date = date,
            widgets = dbLayout,
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

    fun stepDate(forward: Boolean) {
        val today = LocalDate.now()
        _date.update { if (forward) it.plusDays(1).coerceAtMost(today) else it.minusDays(1) }
    }

    fun toggleEditMode() {
        _isEditMode.update { !it }
    }

    fun addWidget(templateId: WidgetTemplateId, colSpan: Int, rowSpan: Int) {
        viewModelScope.launch {
            try {
                widgetLayoutRepo.addWidget(templateId, colSpan, rowSpan)
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

    fun onReorderPersist(orderedWidgets: List<WidgetDefinition>) {
        viewModelScope.launch {
            try {
                widgetLayoutRepo.reorderWidgets(orderedWidgets.map { it.id })
            } catch (e: Exception) {
                Timber.e(e, "Failed to persist widget order")
                _errors.tryEmit("Failed to save widget order")
            }
        }
    }

    fun onWidgetTap(widget: WidgetDefinition) {
        val date = _date.value
        viewModelScope.launch {
            _navigationEvents.tryEmit(widgetTapResolver.resolve(widget.templateId, date))
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
