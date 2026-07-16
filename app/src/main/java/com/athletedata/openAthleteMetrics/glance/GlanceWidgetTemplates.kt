package com.athletedata.openAthleteMetrics.glance

import com.athletedata.openAthleteMetrics.data.model.WidgetTemplateId

/**
 * The subset of native [WidgetTemplateId]s selectable for a home-screen widget instance.
 * Excludes STARRED_LIFESTYLE_BAR/CUSTOM_QUESTIONS_BAR/ACTIVITIES — inherently multi-item,
 * with no existing aggregation down to one tile. Feature-1 imported/custom widgets are out
 * of scope entirely — this list is native templates only.
 *
 * Unlike the generic single-value templates (HR/HRV/RHR/SPO2/STEPS/WEIGHT), which reflow to
 * any granted size via [androidx.glance.LocalSize], each sleep template has one fixed layout
 * and is restricted to its one matching [HomeScreenTier] — see [homeScreenTiers].
 */
val HOME_SCREEN_WIDGET_TEMPLATES: List<WidgetTemplateId> = listOf(
    WidgetTemplateId.HR,
    WidgetTemplateId.HRV,
    WidgetTemplateId.RHR,
    WidgetTemplateId.SLEEP_DURATION,
    WidgetTemplateId.SLEEP_TIMINGS,
    WidgetTemplateId.SLEEP_STAGES,
    WidgetTemplateId.SLEEP_HYPNOGRAM,
    WidgetTemplateId.SLEEP_SUMMARY_SMALL,
    WidgetTemplateId.SLEEP_SUMMARY_LARGE,
    WidgetTemplateId.SPO2,
    WidgetTemplateId.STEPS,
    WidgetTemplateId.WEIGHT,
)

/** The 4 declared home-screen provider-info tiers (small=1x1, medium=2x1, large=2x2, xlarge=2x4). */
enum class HomeScreenTier { SMALL, MEDIUM, LARGE, XLARGE }

/** Which declared tier(s) a template may render at when placed as a home-screen widget. */
fun WidgetTemplateId.homeScreenTiers(): Set<HomeScreenTier> = when (this) {
    WidgetTemplateId.HR, WidgetTemplateId.HRV, WidgetTemplateId.RHR,
    WidgetTemplateId.SPO2, WidgetTemplateId.STEPS, WidgetTemplateId.WEIGHT ->
        HomeScreenTier.entries.toSet()
    WidgetTemplateId.SLEEP_DURATION, WidgetTemplateId.SLEEP_TIMINGS, WidgetTemplateId.SLEEP_STAGES ->
        setOf(HomeScreenTier.SMALL)
    WidgetTemplateId.SLEEP_SUMMARY_SMALL, WidgetTemplateId.SLEEP_HYPNOGRAM ->
        setOf(HomeScreenTier.MEDIUM)
    WidgetTemplateId.SLEEP_SUMMARY_LARGE -> setOf(HomeScreenTier.XLARGE)
    else -> emptySet()
}
