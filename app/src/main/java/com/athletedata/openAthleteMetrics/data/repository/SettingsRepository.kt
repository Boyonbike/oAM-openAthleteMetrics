package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.model.ThemePreference
import com.athletedata.openAthleteMetrics.ui.dailydetail.TileConfig
import kotlinx.coroutines.flow.Flow

/**
 * Contract for persistent user preferences backed by DataStore.
 *
 * The Settings screen calls [setThemePreference]. The theme wrapper
 * composable observes [getThemePreference] to apply the correct colour scheme.
 */
interface SettingsRepository {

    /** Live stream of the user's chosen theme; defaults to [ThemePreference.SYSTEM]. */
    fun getThemePreference(): Flow<ThemePreference>

    /** Persists the user's theme choice. Called by the Settings screen ViewModel. */
    suspend fun setThemePreference(pref: ThemePreference)

    /** Wipes all DataStore preferences (called as part of a full database reset). */
    suspend fun clearAllPreferences()

    /** Last metric key the user viewed in History — null means no selection. */
    fun getHistoryMetricKey(): Flow<String?>

    /** Persist the metric key (or null to clear) so History survives process death. */
    suspend fun setHistoryMetricKey(key: String?)

    /** Live stream of Daily Detail tile ordering and visibility. */
    fun getDailyDetailTileConfig(): Flow<List<TileConfig>>

    /** Persist the full tile config list. */
    suspend fun setDailyDetailTileConfig(configs: List<TileConfig>)

    /** Global rolling window (days) used for automatic baseline range calculation; 30 or 90. Defaults to 30. */
    fun getBaselineWindowDays(): Flow<Int>

    /** Persists the baseline window choice. */
    suspend fun setBaselineWindowDays(days: Int)

    /** Whether the user has permanently dismissed the "switch primary device" confirmation dialog. Defaults to false. */
    fun getRestarConfirmDismissed(): Flow<Boolean>

    /** Persists the user's "don't show again" choice for the re-star confirmation dialog. */
    suspend fun setRestarConfirmDismissed(dismissed: Boolean)
}
