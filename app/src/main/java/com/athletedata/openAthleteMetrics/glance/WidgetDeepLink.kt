package com.athletedata.openAthleteMetrics.glance

/**
 * Intent extra keys carried by a home-screen widget tap into [com.athletedata.openAthleteMetrics.MainActivity].
 * Shared by the writer ([OpenMetricAction]) and the reader ([com.athletedata.openAthleteMetrics.ui.nav.NavGraph])
 * so the two sides can't drift out of sync.
 */
object WidgetDeepLink {
    const val EXTRA_TEMPLATE_ID = "widget_template_id"
    const val EXTRA_DATE = "widget_date"
}
