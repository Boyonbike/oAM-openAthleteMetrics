package com.athletedata.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.athletedata.app.ui.questions.DailyQuestionsScreen
import com.athletedata.app.ui.theme.AthleteDataAppTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AthleteDataAppTheme {
                DailyQuestionsScreen()
            }
        }
    }
}
