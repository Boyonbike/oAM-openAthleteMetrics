package com.athletedata.openAthleteMetrics.ui.overview

import com.athletedata.openAthleteMetrics.data.model.WidgetLayout
import com.athletedata.openAthleteMetrics.ui.dailydetail.DailyDetailSection
import java.time.LocalDate

data class DashboardUiState(
    val date: LocalDate = LocalDate.now(),
    val widgets: List<WidgetLayout> = emptyList(),
    val isEditMode: Boolean = false,
    val hasSeederData: Boolean = false,
)

sealed class DashboardNavigationEvent {
    data class OpenDailyDetail(
        val date: LocalDate,
        val section: DailyDetailSection,
        val metricKey: String? = null,
    ) : DashboardNavigationEvent()
    data class OpenQuestions(val date: LocalDate) : DashboardNavigationEvent()
    data class OpenHabitsTab(val date: LocalDate) : DashboardNavigationEvent()
    object OpenWeightSheet : DashboardNavigationEvent()
}
