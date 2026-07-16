package com.athletedata.openAthleteMetrics.glance

import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.athletedata.openAthleteMetrics.MainActivity
import java.time.LocalDate

/**
 * Tap-to-open: builds the deep-link intent at click time (not render time) so the date
 * extra is never stale, then launches [MainActivity]. [MainActivity] forwards the extras
 * into [com.athletedata.openAthleteMetrics.ui.nav.NavGraph], which resolves the same
 * destination the equivalent in-app dashboard tile would via
 * [com.athletedata.openAthleteMetrics.ui.overview.WidgetTapDestinationResolver].
 */
class OpenMetricAction : ActionCallback {

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val templateId = parameters[templateIdKey]?.takeIf { it.isNotBlank() } ?: return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(WidgetDeepLink.EXTRA_TEMPLATE_ID, templateId)
            putExtra(WidgetDeepLink.EXTRA_DATE, LocalDate.now().toString())
        }
        context.startActivity(intent)
    }

    companion object {
        val templateIdKey = ActionParameters.Key<String>("template_id")
    }
}
