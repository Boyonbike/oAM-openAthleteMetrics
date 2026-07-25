package com.athletedata.openAthleteMetrics.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.appwidget.updateAll
import com.athletedata.openAthleteMetrics.data.model.ThemePreference
import com.athletedata.openAthleteMetrics.glance.MetricGlanceWidget
import com.athletedata.openAthleteMetrics.ui.dailydetail.DEFAULT_TILE_ORDER
import com.athletedata.openAthleteMetrics.ui.dailydetail.TileConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DataStoreSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @ApplicationContext private val context: Context,
) : SettingsRepository {

    /**
     * Live stream of the user's chosen theme preference.
     * Observed by the root composable to select the correct MaterialTheme colour scheme.
     * Defaults to [ThemePreference.SYSTEM] if no value has been saved yet.
     */
    override fun getThemePreference(): Flow<ThemePreference> =
        dataStore.data.map { prefs ->
            val raw = prefs[THEME_KEY] ?: ThemePreference.SYSTEM.name
            runCatching { ThemePreference.valueOf(raw) }.getOrDefault(ThemePreference.SYSTEM)
        }

    /**
     * Persists the user's theme choice to DataStore.
     * Called by the Settings screen ViewModel when the user taps a theme option.
     */
    override suspend fun setThemePreference(pref: ThemePreference) {
        dataStore.edit { prefs ->
            prefs[THEME_KEY] = pref.name
        }
        // Home-screen widgets resolve their colours from this preference (see
        // MetricGlanceWidget.provideGlance) - refresh immediately rather than waiting
        // for the next event-triggered or periodic update.
        MetricGlanceWidget().updateAll(context)
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

    override fun getDailyDetailTileConfig(): Flow<List<TileConfig>> =
        dataStore.data.map { prefs ->
            val raw = prefs[DAILY_DETAIL_TILE_CONFIG_KEY] ?: return@map DEFAULT_TILE_ORDER
            parseTileConfig(raw) ?: DEFAULT_TILE_ORDER
        }

    override suspend fun setDailyDetailTileConfig(configs: List<TileConfig>) {
        dataStore.edit { prefs ->
            prefs[DAILY_DETAIL_TILE_CONFIG_KEY] = encodeTileConfig(configs)
        }
    }

    override fun getBaselineWindowDays(): Flow<Int> =
        dataStore.data.map { prefs -> prefs[BASELINE_WINDOW_DAYS_KEY] ?: 30 }

    override suspend fun setBaselineWindowDays(days: Int) {
        dataStore.edit { prefs ->
            prefs[BASELINE_WINDOW_DAYS_KEY] = days
        }
    }

    override fun getRestarConfirmDismissed(): Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[RESTAR_CONFIRM_DISMISSED_KEY] ?: false }

    override suspend fun setRestarConfirmDismissed(dismissed: Boolean) {
        dataStore.edit { prefs ->
            prefs[RESTAR_CONFIRM_DISMISSED_KEY] = dismissed
        }
    }

    private companion object {
        val THEME_KEY                      = stringPreferencesKey("theme_preference")
        val HISTORY_METRIC_KEY             = stringPreferencesKey("history_metric_key")
        val DAILY_DETAIL_TILE_CONFIG_KEY   = stringPreferencesKey("daily_detail_tile_config")
        val BASELINE_WINDOW_DAYS_KEY       = intPreferencesKey("baseline_window_days")
        val RESTAR_CONFIRM_DISMISSED_KEY   = booleanPreferencesKey("restar_confirm_dismissed")

        fun encodeTileConfig(configs: List<TileConfig>): String {
            val arr = JSONArray()
            configs.forEach { c ->
                arr.put(JSONObject().apply {
                    put("id", c.id)
                    put("visible", c.isVisible)
                    put("order", c.sortOrder)
                })
            }
            return arr.toString()
        }

        fun parseTileConfig(raw: String): List<TileConfig>? = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                TileConfig(
                    id = obj.getString("id"),
                    isVisible = obj.getBoolean("visible"),
                    sortOrder = obj.getInt("order"),
                )
            }
        }.getOrNull()
    }
}
