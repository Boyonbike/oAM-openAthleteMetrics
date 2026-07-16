package com.athletedata.openAthleteMetrics.glance

import com.athletedata.openAthleteMetrics.data.model.WidgetTemplateId

/**
 * The subset of native [WidgetTemplateId]s selectable for a home-screen widget instance.
 * Restricted to single-value-shaped templates — a home-screen tile is scoped to
 * value + label + last-updated, and the excluded templates (STARRED_LIFESTYLE_BAR,
 * CUSTOM_QUESTIONS_BAR, ACTIVITIES) are inherently multi-item, with no existing
 * aggregation down to one value. Feature-1 imported/custom widgets are out of scope
 * entirely — this list is native templates only.
 */
val HOME_SCREEN_WIDGET_TEMPLATES: List<WidgetTemplateId> = listOf(
    WidgetTemplateId.HR,
    WidgetTemplateId.HRV,
    WidgetTemplateId.RHR,
    WidgetTemplateId.SLEEP,
    WidgetTemplateId.SPO2,
    WidgetTemplateId.STEPS,
    WidgetTemplateId.WEIGHT,
)
