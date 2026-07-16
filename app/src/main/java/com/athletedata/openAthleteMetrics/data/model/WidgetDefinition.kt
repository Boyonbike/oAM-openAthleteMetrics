package com.athletedata.openAthleteMetrics.data.model

/** The 15 native widget templates. Replaces the old `WidgetType` sealed class. */
enum class WidgetTemplateId {
    HR, HRV, RHR, SPO2, STEPS,
    SLEEP_DURATION, SLEEP_TIMINGS, SLEEP_STAGES, SLEEP_HYPNOGRAM, SLEEP_SUMMARY_SMALL, SLEEP_SUMMARY_LARGE,
    WEIGHT, STARRED_LIFESTYLE_BAR, CUSTOM_QUESTIONS_BAR, ACTIVITIES;

    /** Metric key used when navigating to Daily Detail scoped to a specific sub-metric. */
    val metricKeyOrNull: String?
        get() = when (this) {
            HR -> "HR"
            HRV -> "HRV"
            RHR -> "RHR"
            SPO2 -> "SPO2"
            STEPS -> "STEPS"
            SLEEP_DURATION, SLEEP_TIMINGS, SLEEP_STAGES, SLEEP_HYPNOGRAM,
            SLEEP_SUMMARY_SMALL, SLEEP_SUMMARY_LARGE -> "SLEEP"
            else -> null
        }
}

/** In-card arrangement, driving which composable renders a definition. */
enum class WidgetLayoutHint {
    SINGLE_METRIC,
    WEIGHT_CARD,
    STARRED_BAR,
    CUSTOM_QUESTIONS_BAR,
    ACTIVITIES_CARD,
    SLEEP_DURATION,
    SLEEP_TIMINGS,
    SLEEP_STAGES,
    SLEEP_HYPNOGRAM,
    SLEEP_SUMMARY_SMALL,
    SLEEP_SUMMARY_LARGE,
}

enum class WidgetChartType { SPARKLINE }

data class MetricBinding(
    val key: DataSourceKey,
    val label: String,
    val unitSuffix: String? = null,
    val decimalPlaces: Int = 0,
    val chartType: WidgetChartType? = null,
)

/**
 * Native widget model. Replaces the old `WidgetType` sealed class + `WidgetSize` enum
 * entirely — every placed widget, native or (in a later phase) imported, is an instance
 * of this one model.
 */
data class WidgetDefinition(
    val id: Long,
    val templateId: WidgetTemplateId,
    val layoutHint: WidgetLayoutHint,
    val colSpan: Int,
    val rowSpan: Int,
    val sequenceOrder: Int,
    val bindings: List<MetricBinding>,
)

/**
 * Template-level shape lookup: layoutHint and bindings are fully determined by
 * [WidgetTemplateId] in this phase (no per-instance binding customization exists yet),
 * so they're reconstructed here rather than persisted — see [WidgetLayoutEntity.toModel].
 */
object WidgetTemplates {

    fun layoutHintFor(templateId: WidgetTemplateId): WidgetLayoutHint = when (templateId) {
        WidgetTemplateId.HR, WidgetTemplateId.HRV, WidgetTemplateId.RHR,
        WidgetTemplateId.SPO2, WidgetTemplateId.STEPS -> WidgetLayoutHint.SINGLE_METRIC
        WidgetTemplateId.SLEEP_DURATION -> WidgetLayoutHint.SLEEP_DURATION
        WidgetTemplateId.SLEEP_TIMINGS -> WidgetLayoutHint.SLEEP_TIMINGS
        WidgetTemplateId.SLEEP_STAGES -> WidgetLayoutHint.SLEEP_STAGES
        WidgetTemplateId.SLEEP_HYPNOGRAM -> WidgetLayoutHint.SLEEP_HYPNOGRAM
        WidgetTemplateId.SLEEP_SUMMARY_SMALL -> WidgetLayoutHint.SLEEP_SUMMARY_SMALL
        WidgetTemplateId.SLEEP_SUMMARY_LARGE -> WidgetLayoutHint.SLEEP_SUMMARY_LARGE
        WidgetTemplateId.WEIGHT -> WidgetLayoutHint.WEIGHT_CARD
        WidgetTemplateId.STARRED_LIFESTYLE_BAR -> WidgetLayoutHint.STARRED_BAR
        WidgetTemplateId.CUSTOM_QUESTIONS_BAR -> WidgetLayoutHint.CUSTOM_QUESTIONS_BAR
        WidgetTemplateId.ACTIVITIES -> WidgetLayoutHint.ACTIVITIES_CARD
    }

    fun bindingsFor(templateId: WidgetTemplateId): List<MetricBinding> = when (templateId) {
        WidgetTemplateId.HR -> listOf(MetricBinding(DataSourceKey.HR, "Heart Rate", "bpm", 0, WidgetChartType.SPARKLINE))
        WidgetTemplateId.HRV -> listOf(MetricBinding(DataSourceKey.HRV, "HRV", "ms", 0, WidgetChartType.SPARKLINE))
        WidgetTemplateId.RHR -> listOf(MetricBinding(DataSourceKey.RHR, "Resting HR", "bpm", 0, WidgetChartType.SPARKLINE))
        WidgetTemplateId.SPO2 -> listOf(MetricBinding(DataSourceKey.SPO2, "SpO₂", "%", 0, WidgetChartType.SPARKLINE))
        WidgetTemplateId.STEPS -> listOf(MetricBinding(DataSourceKey.STEPS, "Steps", "steps", 0, WidgetChartType.SPARKLINE))
        // Sleep widgets are bespoke, like HRV — data comes from SleepDetailProvider directly,
        // bypassing MetricBinding/DataSourceKey/resolveDataSource entirely.
        WidgetTemplateId.SLEEP_DURATION,
        WidgetTemplateId.SLEEP_TIMINGS,
        WidgetTemplateId.SLEEP_STAGES,
        WidgetTemplateId.SLEEP_HYPNOGRAM,
        WidgetTemplateId.SLEEP_SUMMARY_SMALL,
        WidgetTemplateId.SLEEP_SUMMARY_LARGE,
        WidgetTemplateId.WEIGHT,
        WidgetTemplateId.STARRED_LIFESTYLE_BAR,
        WidgetTemplateId.CUSTOM_QUESTIONS_BAR,
        WidgetTemplateId.ACTIVITIES -> emptyList()
    }

    fun definitionFor(
        id: Long,
        templateId: WidgetTemplateId,
        colSpan: Int,
        rowSpan: Int,
        sequenceOrder: Int,
    ): WidgetDefinition = WidgetDefinition(
        id = id,
        templateId = templateId,
        layoutHint = layoutHintFor(templateId),
        colSpan = colSpan,
        rowSpan = rowSpan,
        sequenceOrder = sequenceOrder,
        bindings = bindingsFor(templateId),
    )
}
