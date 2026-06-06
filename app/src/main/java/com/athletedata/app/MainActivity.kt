package com.athletedata.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.athletedata.app.ui.questions.DailyQuestionsScreen
import com.athletedata.app.ui.settings.SettingsScreen
import com.athletedata.app.ui.theme.AthleteDataAppTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AthleteDataAppTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "questions") {
                    composable("questions") {
                        DailyQuestionsScreen(
                            onNavigateToSettings = { navController.navigate("settings") },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            onNavigateBack = { navController.popBackStack() },
                        )
                    }
                }
            }
        }
    }
}
