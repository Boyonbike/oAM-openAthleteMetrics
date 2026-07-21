package com.athletedata.openAthleteMetrics.glance

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.lifecycle.lifecycleScope
import com.athletedata.openAthleteMetrics.data.model.WidgetTemplateId
import com.athletedata.openAthleteMetrics.data.model.WidgetTemplates
import com.athletedata.openAthleteMetrics.ui.theme.AthleteDataAppTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Per-instance configuration: launched by the OS widget host at add-time (declared via
 * `android:configure` on all four provider-info XMLs), lets the user choose which of the
 * [com.athletedata.openAthleteMetrics.glance.HOME_SCREEN_WIDGET_TEMPLATES] valid for this
 * particular instance's tier (see [tierFor]/[HomeScreenTier]) it shows, then persists the
 * choice per-instance via Glance's [androidx.glance.appwidget.state.PreferencesGlanceStateDefinition].
 */
@AndroidEntryPoint
class WidgetConfigActivity : ComponentActivity() {

    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Backing out (or any early return below) must leave the host to discard the
        // pending widget instance cleanly, not leave a broken one behind.
        setResult(RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        // Sleep templates each have one fixed layout and are restricted to their one matching
        // tier (unlike the generic single-value templates, which reflow to any tier) — fail
        // open to the unfiltered list if the provider can't be resolved, rather than blocking
        // setup entirely.
        val tier = tierFor(appWidgetId)
        val candidates = tier?.let { t -> HOME_SCREEN_WIDGET_TEMPLATES.filter { t in it.homeScreenTiers() } }
            ?: HOME_SCREEN_WIDGET_TEMPLATES

        setContent {
            AthleteDataAppTheme {
                WidgetConfigScreen(candidates = candidates, onTemplateSelected = ::onTemplateSelected)
            }
        }
    }

    private fun tierFor(appWidgetId: Int): HomeScreenTier? {
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)?.provider ?: return null
        return when (provider.className) {
            MetricWidgetSmallReceiver::class.java.name -> HomeScreenTier.SMALL
            MetricWidgetMediumReceiver::class.java.name -> HomeScreenTier.MEDIUM
            MetricWidgetLargeReceiver::class.java.name -> HomeScreenTier.LARGE
            MetricWidgetXLargeReceiver::class.java.name -> HomeScreenTier.XLARGE
            else -> null
        }
    }

    private fun onTemplateSelected(templateId: WidgetTemplateId) {
        lifecycleScope.launch {
            try {
                val glanceId = GlanceAppWidgetManager(this@WidgetConfigActivity).getGlanceIdBy(appWidgetId)
                updateAppWidgetState(this@WidgetConfigActivity, glanceId) { prefs ->
                    prefs[TEMPLATE_ID_KEY] = templateId.name
                }
                MetricGlanceWidget().update(this@WidgetConfigActivity, glanceId)

                setResult(
                    RESULT_OK,
                    Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to apply widget template $templateId for widget $appWidgetId")
                // setResult(RESULT_CANCELED) from onCreate remains in effect.
            } finally {
                finish()
            }
        }
    }
}

private fun labelFor(templateId: WidgetTemplateId): String = when (templateId) {
    WidgetTemplateId.SLEEP_DURATION -> "Sleep Duration"
    WidgetTemplateId.SLEEP_TIMINGS -> "Sleep Timings"
    WidgetTemplateId.SLEEP_STAGES -> "Sleep Stages"
    WidgetTemplateId.SLEEP_HYPNOGRAM -> "Hypnogram"
    WidgetTemplateId.SLEEP_SUMMARY_SMALL -> "Sleep Summary (small)"
    WidgetTemplateId.SLEEP_SUMMARY_LARGE -> "Sleep Summary (large)"
    else -> WidgetTemplates.bindingsFor(templateId).firstOrNull()?.label
        ?: templateId.name.lowercase().replaceFirstChar { it.uppercase() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WidgetConfigScreen(candidates: List<WidgetTemplateId>, onTemplateSelected: (WidgetTemplateId) -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Choose a metric") }) },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            candidates.forEach { templateId ->
                Text(
                    text = labelFor(templateId),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onTemplateSelected(templateId) }
                        .padding(20.dp),
                )
            }
        }
    }
}
