package com.athletedata.openAthleteMetrics.ui.overview

import com.athletedata.openAthleteMetrics.data.model.WidgetTemplateId
import com.athletedata.openAthleteMetrics.data.repository.DailyContextRepository
import com.athletedata.openAthleteMetrics.data.repository.QuestionRepository
import com.athletedata.openAthleteMetrics.ui.dailydetail.DailyDetailSection
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single source of truth for "template + date -> in-app destination", used by both
 * the live in-app dashboard tap ([DashboardViewModel.onWidgetTap]) and the home-screen
 * widget tap-to-open deep link ([com.athletedata.openAthleteMetrics.ui.nav.NavGraph]) —
 * so a widget tap always lands exactly where the equivalent dashboard tile would.
 */
@Singleton
class WidgetTapDestinationResolver @Inject constructor(
    private val contextRepo: DailyContextRepository,
    private val questionRepo: QuestionRepository,
) {

    suspend fun resolve(templateId: WidgetTemplateId, date: LocalDate): DashboardNavigationEvent =
        when (templateId) {
            WidgetTemplateId.HR, WidgetTemplateId.HRV, WidgetTemplateId.RHR, WidgetTemplateId.SPO2 ->
                DashboardNavigationEvent.OpenDailyDetail(
                    date,
                    DailyDetailSection.CARDIOVASCULAR,
                    templateId.metricKeyOrNull,
                )
            WidgetTemplateId.SLEEP_DURATION, WidgetTemplateId.SLEEP_TIMINGS, WidgetTemplateId.SLEEP_STAGES,
            WidgetTemplateId.SLEEP_HYPNOGRAM, WidgetTemplateId.SLEEP_SUMMARY_SMALL,
            WidgetTemplateId.SLEEP_SUMMARY_LARGE ->
                DashboardNavigationEvent.OpenDailyDetail(date, DailyDetailSection.SLEEP, "SLEEP")
            WidgetTemplateId.STEPS ->
                DashboardNavigationEvent.OpenDailyDetail(date, DailyDetailSection.ACTIVITY, "STEPS")
            WidgetTemplateId.ACTIVITIES ->
                DashboardNavigationEvent.OpenDailyDetail(date, DailyDetailSection.ACTIVITIES)
            WidgetTemplateId.WEIGHT -> {
                val context = contextRepo.getForDate(date).first()
                if (context?.weightKg != null) {
                    DashboardNavigationEvent.OpenDailyDetail(date, DailyDetailSection.BODY)
                } else {
                    DashboardNavigationEvent.OpenWeightSheet
                }
            }
            WidgetTemplateId.STARRED_LIFESTYLE_BAR -> {
                val responses = questionRepo.getResponsesForDate(date).first()
                if (responses.isEmpty()) {
                    DashboardNavigationEvent.OpenQuestions(date)
                } else {
                    DashboardNavigationEvent.OpenDailyDetail(date, DailyDetailSection.QUESTIONS)
                }
            }
            WidgetTemplateId.CUSTOM_QUESTIONS_BAR -> {
                val responses = questionRepo.getResponsesForDate(date).first()
                val lifestyleIds = questionRepo.getLifestyleQuestionsOnce().map { it.id }.toSet()
                val hasHabitResponse = responses.any { it.questionId !in lifestyleIds }
                if (!hasHabitResponse) {
                    DashboardNavigationEvent.OpenHabitsTab(date)
                } else {
                    DashboardNavigationEvent.OpenDailyDetail(date, DailyDetailSection.QUESTIONS)
                }
            }
        }
}
