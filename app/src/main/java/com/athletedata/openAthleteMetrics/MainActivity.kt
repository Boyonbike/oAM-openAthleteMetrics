package com.athletedata.openAthleteMetrics

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.athletedata.openAthleteMetrics.ble.BleEngine
import com.athletedata.openAthleteMetrics.data.model.ThemePreference
import com.athletedata.openAthleteMetrics.ui.nav.AppNavGraph
import com.athletedata.openAthleteMetrics.ui.settings.SettingsViewModel
import com.athletedata.openAthleteMetrics.ui.theme.AthleteDataAppTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var bleEngine: BleEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bleEngine.autoConnectOnStartup()
        enableEdgeToEdge()
        setContent {
            // Observe theme preference at the root so dark/light/system applies immediately.
            val settingsVm: SettingsViewModel = hiltViewModel()
            val pref by settingsVm.themePreference.collectAsStateWithLifecycle()
            val darkTheme = when (pref) {
                ThemePreference.DARK   -> true
                ThemePreference.LIGHT  -> false
                ThemePreference.SYSTEM -> isSystemInDarkTheme()
            }
            AthleteDataAppTheme(darkTheme = darkTheme) {
                AppNavGraph()
            }
        }
    }
}
