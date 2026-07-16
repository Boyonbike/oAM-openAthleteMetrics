package com.athletedata.openAthleteMetrics.ui.overview.widgets

import com.athletedata.openAthleteMetrics.data.model.WidgetDefinition
import com.athletedata.openAthleteMetrics.data.model.WidgetTemplateId

/**
 * One catalogue entry per native template, with its own explicit [isSingleton] flag —
 * replacing the old catalogue's dual-purposed `allowWide` field (which conflated "hide
 * the size toggle" with "exclude once placed"). Sizing at add-time is fixed to the
 * template's default; resizing after placement is a repository capability
 * ([com.athletedata.openAthleteMetrics.data.repository.WidgetLayoutRepository.resizeWidget])
 * without a dedicated UI in this phase.
 */
data class WidgetCatalogueEntry(
    val templateId: WidgetTemplateId,
    val label: String,
    val group: String,
    val defaultColSpan: Int,
    val defaultRowSpan: Int,
    val isSingleton: Boolean,
)

val WIDGET_CATALOGUE: List<WidgetCatalogueEntry> = listOf(
    WidgetCatalogueEntry(WidgetTemplateId.HR, "Heart Rate", "Metrics", defaultColSpan = 1, defaultRowSpan = 1, isSingleton = false),
    WidgetCatalogueEntry(WidgetTemplateId.HRV, "HRV", "Metrics", defaultColSpan = 1, defaultRowSpan = 1, isSingleton = false),
    WidgetCatalogueEntry(WidgetTemplateId.RHR, "Resting HR", "Metrics", defaultColSpan = 1, defaultRowSpan = 1, isSingleton = false),
    WidgetCatalogueEntry(WidgetTemplateId.SLEEP, "Sleep", "Metrics", defaultColSpan = 1, defaultRowSpan = 1, isSingleton = false),
    WidgetCatalogueEntry(WidgetTemplateId.SPO2, "SpO₂", "Metrics", defaultColSpan = 1, defaultRowSpan = 1, isSingleton = false),
    WidgetCatalogueEntry(WidgetTemplateId.STEPS, "Steps", "Metrics", defaultColSpan = 1, defaultRowSpan = 1, isSingleton = false),
    WidgetCatalogueEntry(WidgetTemplateId.WEIGHT, "Weight", "User Input", defaultColSpan = 2, defaultRowSpan = 1, isSingleton = true),
    WidgetCatalogueEntry(WidgetTemplateId.STARRED_LIFESTYLE_BAR, "Lifestyle", "Bars", defaultColSpan = 2, defaultRowSpan = 1, isSingleton = true),
    WidgetCatalogueEntry(WidgetTemplateId.CUSTOM_QUESTIONS_BAR, "Habits", "Bars", defaultColSpan = 2, defaultRowSpan = 1, isSingleton = true),
    WidgetCatalogueEntry(WidgetTemplateId.ACTIVITIES, "Activities", "Bars", defaultColSpan = 2, defaultRowSpan = 1, isSingleton = true),
)

/** Catalogue entries still eligible to be added, given the widgets already placed. */
fun availableCatalogueEntries(placedWidgets: List<WidgetDefinition>): List<WidgetCatalogueEntry> {
    val placedTemplates = placedWidgets.map { it.templateId }.toSet()
    return WIDGET_CATALOGUE.filter { entry -> !(entry.isSingleton && entry.templateId in placedTemplates) }
}
