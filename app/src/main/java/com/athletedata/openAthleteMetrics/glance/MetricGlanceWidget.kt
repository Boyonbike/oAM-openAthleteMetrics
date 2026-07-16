package com.athletedata.openAthleteMetrics.glance

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
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
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.athletedata.openAthleteMetrics.data.model.ThemePreference
import com.athletedata.openAthleteMetrics.data.model.WidgetTemplateId
import com.athletedata.openAthleteMetrics.data.model.WidgetTemplates
import com.athletedata.openAthleteMetrics.data.repository.WidgetDataContext
import com.athletedata.openAthleteMetrics.data.repository.resolveDataSource
import com.athletedata.openAthleteMetrics.ui.overview.widgets.formatSingleMetricValue
import com.athletedata.openAthleteMetrics.ui.theme.DarkSurface
import com.athletedata.openAthleteMetrics.ui.theme.DarkTextPrimary
import com.athletedata.openAthleteMetrics.ui.theme.DarkTextSecondary
import com.athletedata.openAthleteMetrics.ui.theme.LightSurface
import com.athletedata.openAthleteMetrics.ui.theme.LightTextPrimary
import com.athletedata.openAthleteMetrics.ui.theme.LightTextSecondary
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Home-screen widget for a single native metric: value + label + last-updated, nothing
 * more (Milestone 2 scope). One instance = one [WidgetTemplateId], chosen at add-time via
 * [WidgetConfigActivity] and persisted per-instance via Glance's own [PreferencesGlanceStateDefinition].
 *
 * [SizeMode.Exact] (not [SizeMode.Responsive]) so [Content] reads the actual size the
 * platform grants at render time via [LocalSize] — the user can resize independently of
 * the three tiers declared in the provider-info XMLs, and content must reflow to that
 * real size rather than snapping to the nearest declared tier.
 */
class MetricGlanceWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = EntryPointAccessors.fromApplication(context.applicationContext, WidgetDataEntryPoint::class.java)
        val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        val templateId = prefs[TEMPLATE_ID_KEY]?.let { name -> WidgetTemplateId.entries.find { it.name == name } }
        val data = templateId?.let { fetchMetricData(it, entryPoint) }
        val themePreference = entryPoint.settingsRepository().getThemePreference().first()
        val darkTheme = when (themePreference) {
            ThemePreference.DARK -> true
            ThemePreference.LIGHT -> false
            ThemePreference.SYSTEM -> context.isSystemInNightMode()
        }

        provideContent {
            MetricWidgetContent(templateId, data, darkTheme)
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

private val LAST_UPDATED_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/** Absolute local time, not relative ("2m ago") — Glance content doesn't tick live between refreshes. */
private fun formatLastUpdated(instant: Instant): String =
    LAST_UPDATED_FORMATTER.format(instant.atZone(ZoneId.systemDefault()))

private suspend fun fetchMetricData(templateId: WidgetTemplateId, entryPoint: WidgetDataEntryPoint): MetricWidgetData {
    val today = LocalDate.now()
    return when (templateId) {
        WidgetTemplateId.HR, WidgetTemplateId.RHR, WidgetTemplateId.SLEEP, WidgetTemplateId.SPO2, WidgetTemplateId.STEPS -> {
            val binding = WidgetTemplates.bindingsFor(templateId).first()
            val summary = entryPoint.dailySummaryRepository().getSummaryForDate(today).first()
            val resolved = resolveDataSource(binding.key, WidgetDataContext(today, summary, null, null, 0))
            MetricWidgetData(
                label = binding.label,
                valueText = formatSingleMetricValue(templateId, binding.decimalPlaces, resolved),
                unit = binding.unitSuffix,
                lastUpdatedText = summary?.computedAt?.let(::formatLastUpdated),
            )
        }
        WidgetTemplateId.HRV -> {
            // overnightHrvMs is already null whenever there's no sleep session or insufficient
            // overnight data (see OvernightHrvCalculator) — matches the in-app bespoke pipeline's
            // three-way state without needing sleepRepo/baselineRepo for a value+label tile.
            val summary = entryPoint.dailySummaryRepository().getSummaryForDate(today).first()
            MetricWidgetData(
                label = "HRV",
                valueText = summary?.overnightHrvMs?.let { "%.0f".format(it) },
                unit = "ms",
                lastUpdatedText = summary?.computedAt?.let(::formatLastUpdated),
            )
        }
        WidgetTemplateId.WEIGHT -> {
            val dailyContext = entryPoint.dailyContextRepository().getForDate(today).first()
            MetricWidgetData(
                label = "Weight",
                valueText = dailyContext?.weightKg?.let { "%.1f".format(it) },
                unit = "kg",
                lastUpdatedText = dailyContext?.updatedAt?.let(::formatLastUpdated),
            )
        }
        WidgetTemplateId.STARRED_LIFESTYLE_BAR, WidgetTemplateId.CUSTOM_QUESTIONS_BAR, WidgetTemplateId.ACTIVITIES ->
            MetricWidgetData(label = "", valueText = null, unit = null, lastUpdatedText = null)
    }
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
private fun MetricWidgetContent(templateId: WidgetTemplateId?, data: MetricWidgetData?, darkTheme: Boolean) {
    val size = LocalSize.current
    val showLabelAndTimestamp = size.width >= 120.dp
    val showTimestamp = showLabelAndTimestamp && size.height >= 100.dp
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
        if (templateId == null || data == null) {
            Text(text = "Tap to configure", style = TextStyle(fontSize = 12.sp, color = secondaryText))
        } else {
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
    }
}
