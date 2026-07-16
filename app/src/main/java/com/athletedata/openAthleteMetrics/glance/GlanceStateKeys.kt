package com.athletedata.openAthleteMetrics.glance

import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Per-instance widget state, stored via Glance's own [androidx.glance.appwidget.state.PreferencesGlanceStateDefinition] —
 * each placed widget instance ([androidx.glance.GlanceId]) automatically gets an isolated
 * Preferences file managed by the Glance framework, so no app-level Room table or
 * DataStore file is needed just to remember which metric an instance shows.
 */
val TEMPLATE_ID_KEY = stringPreferencesKey("selected_template_id")
