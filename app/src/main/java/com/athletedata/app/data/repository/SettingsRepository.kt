package com.athletedata.app.data.repository

import com.athletedata.app.data.model.ThemePreference
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
}
