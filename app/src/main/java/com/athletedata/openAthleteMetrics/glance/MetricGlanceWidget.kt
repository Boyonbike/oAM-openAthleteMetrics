package com.athletedata.openAthleteMetrics.glance

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.background
import androidx.glance.unit.ColorProvider
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.athletedata.openAthleteMetrics.data.model.HypnogramSegment
import com.athletedata.openAthleteMetrics.data.model.SleepData
import com.athletedata.openAthleteMetrics.data.model.SleepStage
import com.athletedata.openAthleteMetrics.data.model.ThemePreference
import com.athletedata.openAthleteMetrics.data.model.WidgetTemplateId
import com.athletedata.openAthleteMetrics.data.model.WidgetTemplates
import com.athletedata.openAthleteMetrics.data.repository.WidgetDataContext
import com.athletedata.openAthleteMetrics.data.repository.resolveDataSource
import com.athletedata.openAthleteMetrics.ui.dailydetail.STAGE_COLORS
import com.athletedata.openAthleteMetrics.ui.dailydetail.formatDurationShort
import com.athletedata.openAthleteMetrics.ui.dailydetail.sleepAvgDurationLabel
import com.athletedata.openAthleteMetrics.ui.dailydetail.sleepAvgTimeRangeLabel
import com.athletedata.openAthleteMetrics.ui.dailydetail.sleepTimeRangeLabel
import com.athletedata.openAthleteMetrics.ui.overview.widgets.formatSingleMetricValue
import com.athletedata.openAthleteMetrics.ui.theme.DarkSurface
import com.athletedata.openAthleteMetrics.ui.theme.DarkTextPrimary
import com.athletedata.openAthleteMetrics.ui.theme.DarkTextSecondary
import com.athletedata.openAthleteMetrics.ui.theme.LightSurface
import com.athletedata.openAthleteMetrics.ui.theme.LightTextPrimary
import com.athletedata.openAthleteMetrics.ui.theme.LightTextSecondary
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Home-screen widget for a single native metric or sleep widget. One instance = one
 * [WidgetTemplateId], chosen at add-time via [WidgetConfigActivity] (filtered to the
 * templates valid for this instance's tier — see [homeScreenTiers]) and persisted
 * per-instance via Glance's own [PreferencesGlanceStateDefinition].
 *
 * [SizeMode.Exact] (not [SizeMode.Responsive]) so [Content] reads the actual size the
 * platform grants at render time via [LocalSize] — the user can resize independently of
 * the declared tiers in the provider-info XMLs, and content must reflow to that real size
 * rather than snapping to the nearest declared tier.
 */
class MetricGlanceWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = EntryPointAccessors.fromApplication(context.applicationContext, WidgetDataEntryPoint::class.java)
        val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        val templateId = prefs[TEMPLATE_ID_KEY]?.let { name -> WidgetTemplateId.entries.find { it.name == name } }

        val (content, darkTheme) = runCatching {
            val content = templateId?.let { fetchGlanceContent(it, entryPoint) }
            val themePreference = entryPoint.settingsRepository().getThemePreference().first()
            val darkTheme = when (themePreference) {
                ThemePreference.DARK -> true
                ThemePreference.LIGHT -> false
                ThemePreference.SYSTEM -> context.isSystemInNightMode()
            }
            content to darkTheme
        }.onFailure { e ->
            Timber.e(e, "Failed to load glance content for widget $id (template=$templateId)")
        }.getOrDefault(null to context.isSystemInNightMode())

        provideContent {
            MetricWidgetContent(templateId, content, darkTheme)
        }
    }
}

/** Mirrors [android.content.res.Configuration.UI_MODE_NIGHT_MASK] used by [ThemePreference.SYSTEM]. */
private fun Context.isSystemInNightMode(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

private data class MetricWidgetData(
    val label: String,
    val valueText: String?,
    val unit: String?,
    val lastUpdatedText: String?,
)

private data class StageAmount(
    val label: String,
    val stage: SleepStage,
    val minutesText: String?,
    val avgMinutesText: String?,
)

/**
 * The shape rendered by [MetricWidgetContent] — replaces a single flat [MetricWidgetData]
 * path now that sleep widgets need richer, multi-value layouts. [Simple] covers every
 * generic single-value template (HR/HRV/RHR/SPO2/STEPS/WEIGHT) unchanged from before.
 */
private sealed class GlanceWidgetContent {
    data class Simple(val data: MetricWidgetData) : GlanceWidgetContent()
    data class Duration(val valueText: String?, val avgText: String?) : GlanceWidgetContent()
    data class Timings(val rangeText: String?, val avgRangeText: String?) : GlanceWidgetContent()
    data class Stages(val stages: List<StageAmount>) : GlanceWidgetContent()
    data class HypnogramOnly(
        val segments: List<HypnogramSegment>,
        val onsetLabel: String?,
        val wakeLabel: String?,
    ) : GlanceWidgetContent()
    data class SummarySmall(val duration: Duration, val timings: Timings, val stages: Stages) : GlanceWidgetContent()
    data class SummaryLarge(val small: SummarySmall, val hypnogram: HypnogramOnly) : GlanceWidgetContent()
    object Empty : GlanceWidgetContent()
    object NoData : GlanceWidgetContent()
}

private val LAST_UPDATED_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/** Absolute local time, not relative ("2m ago") — Glance content doesn't tick live between refreshes. */
private fun formatLastUpdated(instant: Instant): String =
    LAST_UPDATED_FORMATTER.format(instant.atZone(ZoneId.systemDefault()))

private suspend fun fetchGlanceContent(templateId: WidgetTemplateId, entryPoint: WidgetDataEntryPoint): GlanceWidgetContent {
    val today = LocalDate.now()
    return when (templateId) {
        WidgetTemplateId.HR, WidgetTemplateId.RHR, WidgetTemplateId.SPO2, WidgetTemplateId.STEPS -> {
            val binding = WidgetTemplates.bindingsFor(templateId).first()
            val summary = entryPoint.dailySummaryRepository().getSummaryForDate(today).first()
            val resolved = resolveDataSource(binding.key, WidgetDataContext(today, summary, null, null, 0))
            val valueText = formatSingleMetricValue(binding.decimalPlaces, resolved)
            GlanceWidgetContent.Simple(
                MetricWidgetData(
                    label = binding.label,
                    valueText = valueText,
                    unit = binding.unitSuffix,
                    lastUpdatedText = valueText?.let { summary?.computedAt?.let(::formatLastUpdated) },
                ),
            )
        }
        WidgetTemplateId.HRV -> {
            // overnightHrvMs is already null whenever there's no sleep session or insufficient
            // overnight data (see OvernightHrvCalculator) — matches the in-app bespoke pipeline's
            // three-way state without needing sleepRepo/baselineRepo for a value+label tile.
            val summary = entryPoint.dailySummaryRepository().getSummaryForDate(today).first()
            val valueText = summary?.overnightHrvMs?.let { "%.0f".format(it) }
            GlanceWidgetContent.Simple(
                MetricWidgetData(
                    label = "HRV",
                    valueText = valueText,
                    unit = "ms",
                    lastUpdatedText = valueText?.let { summary?.computedAt?.let(::formatLastUpdated) },
                ),
            )
        }
        WidgetTemplateId.WEIGHT -> {
            val dailyContext = entryPoint.dailyContextRepository().getForDate(today).first()
            val valueText = dailyContext?.weightKg?.let { "%.1f".format(it) }
            GlanceWidgetContent.Simple(
                MetricWidgetData(
                    label = "Weight",
                    valueText = valueText,
                    unit = "kg",
                    lastUpdatedText = valueText?.let { dailyContext?.updatedAt?.let(::formatLastUpdated) },
                ),
            )
        }
        WidgetTemplateId.SLEEP_DURATION, WidgetTemplateId.SLEEP_TIMINGS, WidgetTemplateId.SLEEP_STAGES,
        WidgetTemplateId.SLEEP_HYPNOGRAM, WidgetTemplateId.SLEEP_SUMMARY_SMALL, WidgetTemplateId.SLEEP_SUMMARY_LARGE -> {
            val data = entryPoint.sleepDetailProvider().getSleepDataOnce(today)
            if (data == null) GlanceWidgetContent.NoData else buildSleepGlanceContent(templateId, data)
        }
        WidgetTemplateId.STARRED_LIFESTYLE_BAR, WidgetTemplateId.CUSTOM_QUESTIONS_BAR, WidgetTemplateId.ACTIVITIES ->
            GlanceWidgetContent.Empty
    }
}

private fun buildDurationContent(data: SleepData) = GlanceWidgetContent.Duration(
    valueText = data.formattedDuration,
    avgText = sleepAvgDurationLabel(data.averages),
)

private fun buildTimingsContent(data: SleepData) = GlanceWidgetContent.Timings(
    rangeText = sleepTimeRangeLabel(data),
    avgRangeText = sleepAvgTimeRangeLabel(data.averages),
)

private fun buildStagesContent(data: SleepData) = GlanceWidgetContent.Stages(
    stages = listOf(
        StageAmount(
            "D", SleepStage.DEEP,
            data.deepMinutes?.let { formatDurationShort(it) },
            data.averages.avgDeepMinutes?.let { formatDurationShort(it) },
        ),
        StageAmount(
            "L", SleepStage.LIGHT,
            data.lightMinutes?.let { formatDurationShort(it) },
            data.averages.avgLightMinutes?.let { formatDurationShort(it) },
        ),
        StageAmount(
            "R", SleepStage.REM,
            data.remMinutes?.let { formatDurationShort(it) },
            data.averages.avgRemMinutes?.let { formatDurationShort(it) },
        ),
        StageAmount(
            "A", SleepStage.AWAKE,
            data.awakeMinutes?.let { formatDurationShort(it) },
            data.averages.avgAwakeMinutes?.let { formatDurationShort(it) },
        ),
    ),
)

private fun buildHypnogramContent(data: SleepData) = GlanceWidgetContent.HypnogramOnly(
    segments = data.hypnogramSegments,
    onsetLabel = data.onsetTimeLabel,
    wakeLabel = data.wakeTimeLabel,
)

private fun buildSleepGlanceContent(templateId: WidgetTemplateId, data: SleepData): GlanceWidgetContent = when (templateId) {
    WidgetTemplateId.SLEEP_DURATION -> buildDurationContent(data)
    WidgetTemplateId.SLEEP_TIMINGS -> buildTimingsContent(data)
    WidgetTemplateId.SLEEP_STAGES -> buildStagesContent(data)
    WidgetTemplateId.SLEEP_HYPNOGRAM -> buildHypnogramContent(data)
    WidgetTemplateId.SLEEP_SUMMARY_SMALL ->
        GlanceWidgetContent.SummarySmall(buildDurationContent(data), buildTimingsContent(data), buildStagesContent(data))
    WidgetTemplateId.SLEEP_SUMMARY_LARGE ->
        GlanceWidgetContent.SummaryLarge(
            GlanceWidgetContent.SummarySmall(buildDurationContent(data), buildTimingsContent(data), buildStagesContent(data)),
            buildHypnogramContent(data),
        )
    else -> GlanceWidgetContent.Empty
}

/**
 * Mirrors the in-app dashboard tile's colours for the given effective theme (resolved from the
 * user's [ThemePreference], not the OS setting directly — see [MetricGlanceWidget.provideGlance]).
 * [MaterialTheme.colorScheme.surface] container, medium (12dp) shape.
 */
private fun tileBackground(darkTheme: Boolean) = ColorProvider(if (darkTheme) DarkSurface else LightSurface)

/** Mirrors [TypographyTitle]/[TypographyMeta]'s onSurfaceVariant color. */
private fun tileSecondaryText(darkTheme: Boolean) = ColorProvider(if (darkTheme) DarkTextSecondary else LightTextSecondary)

/** Mirrors [TypographyValue]'s onSurface color (the Card's default content color). */
private fun tilePrimaryText(darkTheme: Boolean) = ColorProvider(if (darkTheme) DarkTextPrimary else LightTextPrimary)

@Composable
private fun MetricWidgetContent(templateId: WidgetTemplateId?, content: GlanceWidgetContent?, darkTheme: Boolean) {
    val secondaryText = tileSecondaryText(darkTheme)
    val primaryText = tilePrimaryText(darkTheme)

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(tileBackground(darkTheme))
            .cornerRadius(12.dp)
            .padding(12.dp)
            .clickable(
                actionRunCallback<OpenMetricAction>(
                    actionParametersOf(OpenMetricAction.templateIdKey to (templateId?.name.orEmpty())),
                ),
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        when {
            templateId == null || content == null || content is GlanceWidgetContent.Empty ->
                Text(text = "Tap to configure", style = TextStyle(fontSize = 12.sp, color = secondaryText))
            content is GlanceWidgetContent.NoData ->
                Text(text = "No sleep data", style = TextStyle(fontSize = 12.sp, color = secondaryText))
            content is GlanceWidgetContent.Simple -> SimpleTileContent(content.data, secondaryText, primaryText)
            content is GlanceWidgetContent.Duration ->
                SleepStatTileContent("Sleep Duration", content.valueText, content.avgText, secondaryText, primaryText)
            content is GlanceWidgetContent.Timings ->
                SleepStatTileContent("Sleep Timings", content.rangeText, content.avgRangeText, secondaryText, primaryText)
            content is GlanceWidgetContent.Stages -> SleepStagesTileContent(content, secondaryText, primaryText)
            content is GlanceWidgetContent.HypnogramOnly -> SleepHypnogramTileContent(content, secondaryText, primaryText)
            content is GlanceWidgetContent.SummarySmall -> SleepSummarySmallTileContent(content, secondaryText, primaryText)
            content is GlanceWidgetContent.SummaryLarge -> {
                Column {
                    SleepSummarySmallTileContent(content.small, secondaryText, primaryText)
                    Box(modifier = GlanceModifier.padding(top = 10.dp)) {
                        SleepHypnogramTileContent(content.hypnogram, secondaryText, primaryText)
                    }
                }
            }
        }
    }
}

@Composable
private fun SimpleTileContent(data: MetricWidgetData, secondaryText: ColorProvider, primaryText: ColorProvider) {
    val size = LocalSize.current
    val showLabelAndTimestamp = size.width >= 120.dp
    val showTimestamp = showLabelAndTimestamp && size.height >= 100.dp
    Column {
        if (showLabelAndTimestamp) {
            Text(text = data.label, style = TextStyle(fontSize = 12.sp, color = secondaryText))
        }
        Text(
            text = buildString {
                append(data.valueText ?: "--")
                if (data.valueText != null && data.unit != null) {
                    append(" ")
                    append(data.unit)
                }
            },
            style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, color = primaryText),
        )
        if (showTimestamp && data.lastUpdatedText != null) {
            Text(text = "Updated ${data.lastUpdatedText}", style = TextStyle(fontSize = 10.sp, color = secondaryText))
        }
    }
}

@Composable
private fun SleepStatTileContent(
    label: String,
    valueText: String?,
    avgText: String?,
    secondaryText: ColorProvider,
    primaryText: ColorProvider,
) {
    val showLabel = LocalSize.current.width >= 120.dp
    Column {
        if (showLabel) {
            Text(text = label, style = TextStyle(fontSize = 12.sp, color = secondaryText))
        }
        Text(text = valueText ?: "--", style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = primaryText))
        if (avgText != null) {
            Text(text = avgText, style = TextStyle(fontSize = 10.sp, color = secondaryText))
        }
    }
}

@Composable
private fun SleepStagesTileContent(content: GlanceWidgetContent.Stages, secondaryText: ColorProvider, primaryText: ColorProvider) {
    // 4 stage cells + labels + averages don't fit the 90dp small-tier floor — the one place
    // the "always show baseline" rule flexes, and only at that absolute floor size.
    val showAvg = LocalSize.current.width >= 120.dp || LocalSize.current.height >= 120.dp
    Column {
        Text(text = "Sleep Stages", style = TextStyle(fontSize = 11.sp, color = secondaryText))
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            StageCell(content.stages[0], showAvg, secondaryText, primaryText, GlanceModifier.defaultWeight())
            StageCell(content.stages[1], showAvg, secondaryText, primaryText, GlanceModifier.defaultWeight())
        }
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            StageCell(content.stages[2], showAvg, secondaryText, primaryText, GlanceModifier.defaultWeight())
            StageCell(content.stages[3], showAvg, secondaryText, primaryText, GlanceModifier.defaultWeight())
        }
    }
}

@Composable
private fun StageCell(
    stage: StageAmount,
    showAvg: Boolean,
    secondaryText: ColorProvider,
    primaryText: ColorProvider,
    modifier: GlanceModifier,
) {
    Column(modifier = modifier) {
        Text(text = "${stage.label} ${stage.minutesText ?: "--"}", style = TextStyle(fontSize = 12.sp, color = primaryText))
        if (showAvg && stage.avgMinutesText != null) {
            Text(text = "Avg ${stage.avgMinutesText}", style = TextStyle(fontSize = 9.sp, color = secondaryText))
        }
    }
}

@Composable
private fun SleepHypnogramTileContent(
    content: GlanceWidgetContent.HypnogramOnly,
    secondaryText: ColorProvider,
    primaryText: ColorProvider,
) {
    Column {
        Text(text = "Hypnogram", style = TextStyle(fontSize = 12.sp, color = secondaryText))
        if (content.segments.isEmpty()) {
            Text(text = "No hypnogram data", style = TextStyle(fontSize = 11.sp, color = secondaryText))
        } else {
            GlanceHypnogramBar(content.segments, modifier = GlanceModifier.fillMaxWidth().height(24.dp))
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                Text(
                    text = content.onsetLabel ?: "",
                    style = TextStyle(fontSize = 10.sp, color = secondaryText),
                    modifier = GlanceModifier.defaultWeight(),
                )
                Text(text = content.wakeLabel ?: "", style = TextStyle(fontSize = 10.sp, color = secondaryText))
            }
        }
    }
}

/**
 * Segmented bar via explicit per-segment [width] (not Row weights — Glance's [defaultWeight]
 * is always equal-split, with no proportional-weight overload like Compose's `weight(Float)`),
 * computed from [LocalSize] minus the outer tile's 12dp*2 padding. Hourly tick labels are
 * dropped (too dense at home-screen scale) — only onset/wake render, below the bar.
 */
@Composable
private fun GlanceHypnogramBar(segments: List<HypnogramSegment>, modifier: GlanceModifier = GlanceModifier) {
    val availableWidth: Dp = (LocalSize.current.width - 24.dp).coerceAtLeast(1.dp)
    val startMs = segments.minOf { it.startMs }
    val endMs = segments.maxOf { it.endMs }
    val totalMs = (endMs - startMs).toFloat().coerceAtLeast(1f)
    Row(modifier = modifier) {
        var prevEndMs = startMs
        segments.forEach { seg ->
            val gapMs = seg.startMs - prevEndMs
            if (gapMs > 0) {
                val gapWidth = availableWidth * (gapMs / totalMs)
                Box(modifier = GlanceModifier.width(gapWidth).fillMaxHeight()) {}
            }
            val widthFrac = (seg.endMs - seg.startMs) / totalMs
            val segWidth = (availableWidth * widthFrac).coerceAtLeast(1.dp)
            Box(
                modifier = GlanceModifier
                    .width(segWidth)
                    .fillMaxHeight()
                    .background(ColorProvider(STAGE_COLORS.getValue(seg.stage))),
            ) {}
            prevEndMs = seg.endMs
        }
    }
}

@Composable
private fun SleepSummarySmallTileContent(
    content: GlanceWidgetContent.SummarySmall,
    secondaryText: ColorProvider,
    primaryText: ColorProvider,
) {
    Column {
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = content.duration.valueText ?: "--",
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = primaryText),
                )
                content.duration.avgText?.let {
                    Text(text = it, style = TextStyle(fontSize = 9.sp, color = secondaryText))
                }
            }
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = content.timings.rangeText ?: "--",
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = primaryText),
                )
                content.timings.avgRangeText?.let {
                    Text(text = it, style = TextStyle(fontSize = 9.sp, color = secondaryText))
                }
            }
        }
        Row(modifier = GlanceModifier.fillMaxWidth().padding(top = 4.dp)) {
            content.stages.stages.forEach { stage ->
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = "${stage.label} ${stage.minutesText ?: "--"}",
                        style = TextStyle(fontSize = 11.sp, color = primaryText),
                    )
                    stage.avgMinutesText?.let {
                        Text(text = "Avg $it", style = TextStyle(fontSize = 9.sp, color = secondaryText))
                    }
                }
            }
        }
    }
}
