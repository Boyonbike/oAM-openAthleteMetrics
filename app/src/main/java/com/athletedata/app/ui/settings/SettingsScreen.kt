package com.athletedata.app.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.athletedata.app.BuildConfig
import com.athletedata.app.ui.components.SectionHeader
import com.athletedata.app.data.model.ThemePreference
import com.athletedata.app.seeder.SeederState
import com.athletedata.app.seeder.SeederViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
) {
    val theme by settingsViewModel.themePreference.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SectionHeader("Theme")
            Spacer(modifier = Modifier.height(8.dp))
            ThemeSelector(current = theme, onSelect = settingsViewModel::setTheme)

            if (BuildConfig.DEBUG) {
                val seederViewModel: SeederViewModel = hiltViewModel()
                val seederState by seederViewModel.state.collectAsStateWithLifecycle()
                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                SectionHeader("Debug", color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(8.dp))
                SeederSection(
                    state = seederState,
                    onSeedThirtyDays = seederViewModel::seedThirtyDays,
                    onSeedToday = seederViewModel::seedToday,
                    onClearData = seederViewModel::clearSeederData,
                    onDismissResult = seederViewModel::resetState,
                )
            }
        }
    }
}

@Composable
private fun ThemeSelector(
    current: ThemePreference,
    onSelect: (ThemePreference) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            ThemePreference.LIGHT  to "Light",
            ThemePreference.DARK   to "Dark",
            ThemePreference.SYSTEM to "System",
        ).forEach { (pref, label) ->
            FilterChip(
                selected = current == pref,
                onClick = { onSelect(pref) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun SeederSection(
    state: SeederState,
    onSeedThirtyDays: () -> Unit,
    onSeedToday: () -> Unit,
    onClearData: () -> Unit,
    onDismissResult: () -> Unit,
) {
    val isRunning = state is SeederState.Running

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = onSeedThirtyDays,
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Seed 30 days") }

        OutlinedButton(
            onClick = onSeedToday,
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Seed today only") }

        OutlinedButton(
            onClick = onClearData,
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) { Text("Clear seeder data") }

        AnimatedVisibility(visible = isRunning) {
            Column {
                val progress = (state as? SeederState.Running)?.progress ?: 0f
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        when (state) {
            is SeederState.Done -> {
                Text("Done!", color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = onDismissResult) { Text("OK") }
            }
            is SeederState.Error -> {
                Text(
                    text = "Error: ${state.message}",
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onDismissResult) { Text("Dismiss") }
            }
            else -> Unit
        }
    }
}
