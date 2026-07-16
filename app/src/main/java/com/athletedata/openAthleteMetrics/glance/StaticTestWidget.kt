package com.athletedata.openAthleteMetrics.glance

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.text.Text

/**
 * Milestone 1 of the home-screen widget rollout: a hardcoded static string with no
 * data access at all, proving the Gradle/manifest/provider-info plumbing works before
 * any real widget (see [com.athletedata.openAthleteMetrics.glance.MetricGlanceWidget])
 * is built on top of it.
 */
class StaticTestWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            Text("OAM widget works")
        }
    }
}
