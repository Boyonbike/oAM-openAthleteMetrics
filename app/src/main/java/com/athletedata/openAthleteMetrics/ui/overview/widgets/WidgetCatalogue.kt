package com.athletedata.openAthleteMetrics.ui.overview.widgets

import com.athletedata.openAthleteMetrics.data.model.WidgetDefinition
import com.athletedata.openAthleteMetrics.data.model.WidgetTemplateId

/** The 5 top-level categories shown in the Add Widget menu, mirroring the daily detail page's sections. */
enum class WidgetCatalogueCategory(val displayName: String) {
    CARDIOVASCULAR("Cardiovascular"),
    SLEEP("Sleep"),
    ACTIVITY("Activity"),
    BODY("Body"),
    CHECK_IN("Check-in"),
}

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
    val typeLabel: String,
    val defaultColSpan: Int,
    val defaultRowSpan: Int,
    val isSingleton: Boolean,
)

/** A metric type within a category, e.g. "Heart Rate" — holds the widget(s) available for it. */
data class WidgetCatalogueMetricType(
    val id: String,
    val label: String,
    val widgets: List<WidgetCatalogueEntry>,
)

data class WidgetCatalogueCategoryGroup(
    val category: WidgetCatalogueCategory,
    val metricTypes: List<WidgetCatalogueMetricType>,
)

val WIDGET_CATALOGUE: List<WidgetCatalogueCategoryGroup> = listOf(
    WidgetCatalogueCategoryGroup(
        category = WidgetCatalogueCategory.CARDIOVASCULAR,
        metricTypes = listOf(
            WidgetCatalogueMetricType(
                id = "HR",
                label = "Heart Rate",
                widgets = listOf(
                    WidgetCatalogueEntry(WidgetTemplateId.HR, "Heart Rate", "Metric", defaultColSpan = 1, defaultRowSpan = 1, isSingleton = false),
                    WidgetCatalogueEntry(WidgetTemplateId.RHR, "Resting HR", "Metric", defaultColSpan = 1, defaultRowSpan = 1, isSingleton = false),
                ),
            ),
            WidgetCatalogueMetricType(
                id = "HRV",
                label = "HRV",
                widgets = listOf(
                    WidgetCatalogueEntry(WidgetTemplateId.HRV, "HRV", "Metric", defaultColSpan = 1, defaultRowSpan = 1, isSingleton = false),
                ),
            ),
            WidgetCatalogueMetricType(
                id = "SPO2",
                label = "SpO₂",
                widgets = listOf(
                    WidgetCatalogueEntry(WidgetTemplateId.SPO2, "SpO₂", "Metric", defaultColSpan = 1, defaultRowSpan = 1, isSingleton = false),
                ),
            ),
        ),
    ),
    WidgetCatalogueCategoryGroup(
        category = WidgetCatalogueCategory.SLEEP,
        metricTypes = listOf(
            WidgetCatalogueMetricType(
                id = "SLEEP",
                label = "Sleep",
                widgets = listOf(
                    WidgetCatalogueEntry(WidgetTemplateId.SLEEP, "Sleep", "Metric", defaultColSpan = 1, defaultRowSpan = 1, isSingleton = false),
                ),
            ),
        ),
    ),
    WidgetCatalogueCategoryGroup(
        category = WidgetCatalogueCategory.ACTIVITY,
        metricTypes = listOf(
            WidgetCatalogueMetricType(
                id = "STEPS",
                label = "Steps",
                widgets = listOf(
                    WidgetCatalogueEntry(WidgetTemplateId.STEPS, "Steps", "Metric", defaultColSpan = 1, defaultRowSpan = 1, isSingleton = false),
                ),
            ),
            WidgetCatalogueMetricType(
                id = "ACTIVITIES",
                label = "Activities",
                widgets = listOf(
                    WidgetCatalogueEntry(WidgetTemplateId.ACTIVITIES, "Activities", "Bar", defaultColSpan = 2, defaultRowSpan = 1, isSingleton = true),
                ),
            ),
        ),
    ),
    WidgetCatalogueCategoryGroup(
        category = WidgetCatalogueCategory.BODY,
        metricTypes = listOf(
            WidgetCatalogueMetricType(
                id = "WEIGHT",
                label = "Weight",
                widgets = listOf(
                    WidgetCatalogueEntry(WidgetTemplateId.WEIGHT, "Weight", "Log", defaultColSpan = 2, defaultRowSpan = 1, isSingleton = true),
                ),
            ),
        ),
    ),
    WidgetCatalogueCategoryGroup(
        category = WidgetCatalogueCategory.CHECK_IN,
        metricTypes = listOf(
            WidgetCatalogueMetricType(
                id = "LIFESTYLE",
                label = "Lifestyle",
                widgets = listOf(
                    WidgetCatalogueEntry(WidgetTemplateId.STARRED_LIFESTYLE_BAR, "Lifestyle", "Bar", defaultColSpan = 2, defaultRowSpan = 1, isSingleton = true),
                ),
            ),
            WidgetCatalogueMetricType(
                id = "HABITS",
                label = "Habits",
                widgets = listOf(
                    WidgetCatalogueEntry(WidgetTemplateId.CUSTOM_QUESTIONS_BAR, "Habits", "Bar", defaultColSpan = 2, defaultRowSpan = 1, isSingleton = true),
                ),
            ),
        ),
    ),
)

/**
 * The catalogue pruned to what's still eligible to add, given the widgets already placed.
 * Metric types left with no widgets, and categories left with no metric types, are dropped
 * entirely rather than shown disabled.
 */
fun availableCatalogue(placedWidgets: List<WidgetDefinition>): List<WidgetCatalogueCategoryGroup> {
    val placedTemplates = placedWidgets.map { it.templateId }.toSet()
    return WIDGET_CATALOGUE.mapNotNull { group ->
        val metricTypes = group.metricTypes.mapNotNull { metricType ->
            val widgets = metricType.widgets.filter { !(it.isSingleton && it.templateId in placedTemplates) }
            if (widgets.isEmpty()) null else metricType.copy(widgets = widgets)
        }
        if (metricTypes.isEmpty()) null else group.copy(metricTypes = metricTypes)
    }
}
