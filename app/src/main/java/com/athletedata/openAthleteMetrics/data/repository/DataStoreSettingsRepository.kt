package com.athletedata.openAthleteMetrics.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.athletedata.openAthleteMetrics.data.model.ThemePreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DataStoreSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    /**
     * Live stream of the user's chosen theme preference.
     * Observed by the root composable to select the correct MaterialTheme colour scheme.
     * Defaults to [ThemePreference.SYSTEM] if no value has been saved yet.
     */
    override fun getThemePreference(): Flow<ThemePreference> =
        dataStore.data.map { prefs ->
            val raw = prefs[THEME_KEY] ?: ThemePreference.SYSTEM.name
            ThemePreference.valueOf(raw)
        }

    /**
     * Persists the user's theme choice to DataStore.
     * Called by the Settings screen ViewModel when the user taps a theme option.
     */
    override suspend fun setThemePreference(pref: ThemePreference) {
        dataStore.edit { prefs ->
            prefs[THEME_KEY] = pref.name
        }
    }

    override suspend fun clearAllPreferences() {
        dataStore.edit { it.clear() }
    }

    override fun getHistoryMetricKey(): Flow<String?> =
        dataStore.data.map { it[HISTORY_METRIC_KEY] }

    override suspend fun setHistoryMetricKey(key: String?) {
        dataStore.edit { prefs ->
            if (key != null) prefs[HISTORY_METRIC_KEY] = key
            else prefs.remove(HISTORY_METRIC_KEY)
        }
    }

    private companion object {
        val THEME_KEY          = stringPreferencesKey("theme_preference")
        val HISTORY_METRIC_KEY = stringPreferencesKey("history_metric_key")
    }
}
