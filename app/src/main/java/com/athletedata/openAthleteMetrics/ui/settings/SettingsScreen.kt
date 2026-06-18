package com.athletedata.openAthleteMetrics.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.athletedata.openAthleteMetrics.BuildConfig
import com.athletedata.openAthleteMetrics.data.model.ThemePreference
import com.athletedata.openAthleteMetrics.seeder.SeederState
import com.athletedata.openAthleteMetrics.seeder.SeederViewModel
import com.athletedata.openAthleteMetrics.ui.components.SectionHeader
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToDashboard: () -> Unit,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
) {
    val theme by settingsViewModel.themePreference.collectAsStateWithLifecycle()
    val uiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Collect one-shot effects: snackbars (suspending — waits for dismissal) then navigation.
    LaunchedEffect(Unit) {
        settingsViewModel.effects.collect { effect ->
            when (effect) {
                is SettingsEffect.ShowSnackbar -> snackbarHostState.showSnackbar(effect.message)
                is SettingsEffect.NavigateToDashboard -> onNavigateToDashboard()
            }
        }
    }

    // SAF launchers must be registered in the composable, results forwarded to the VM.
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> uri?.let { settingsViewModel.exportDatabase(it) } }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { settingsViewModel.onImportUriChosen(it) } }

    // ── Import confirmation dialog ─────────────────────────────────────────────
    if (uiState.importPendingUri != null) {
        AlertDialog(
            onDismissRequest = settingsViewModel::dismissImportDialog,
            title = { Text("Replace database?") },
            text = {
                Text(
                    "This will permanently replace all current data with the contents of the " +
                        "selected file. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = settingsViewModel::confirmImport) { Text("Replace") }
            },
            dismissButton = {
                TextButton(onClick = settingsViewModel::dismissImportDialog) { Text("Cancel") }
            },
        )
    }

    // ── Reset step 1 — confirm intent ─────────────────────────────────────────
    if (uiState.resetStep == ResetStep.Confirm) {
        AlertDialog(
            onDismissRequest = settingsViewModel::dismissReset,
            title = { Text("Reset all data?") },
            text = {
                Text(
                    "This will permanently delete all your metrics, questions, habits, and logs. " +
                        "There is no undo.",
                )
            },
            confirmButton = {
                TextButton(onClick = settingsViewModel::onResetContinued) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = settingsViewModel::dismissReset) { Text("Cancel") }
            },
        )
    }

    // ── Reset step 2 — type DELETE ────────────────────────────────────────────
    if (uiState.resetStep == ResetStep.TypeDelete) {
        AlertDialog(
            onDismissRequest = settingsViewModel::dismissReset,
            title = { Text("Type DELETE to confirm") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This action cannot be undone.")
                    OutlinedTextField(
                        value = uiState.resetTypedText,
                        onValueChange = settingsViewModel::onResetTyped,
                        placeholder = { Text("Type DELETE") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = settingsViewModel::confirmReset,
                    enabled = uiState.resetTypedText.trim() == "DELETE",
                ) { Text("Delete everything") }
            },
            dismissButton = {
                TextButton(onClick = settingsViewModel::dismissReset) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .padding(bottom = 80.dp),
        ) {
            // ── Section 1: Theme ──────────────────────────────────────────────
            SectionHeader("Theme")
            Spacer(Modifier.height(8.dp))
            ThemeSelector(current = theme, onSelect = settingsViewModel::setTheme)

            // ── Section 2: Backup ─────────────────────────────────────────────
            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            SectionHeader("Backup")
            Spacer(Modifier.height(8.dp))
            BackupSection(
                isBusy = uiState.isBusy,
                onExport = {
                    exportLauncher.launch("athlete_data_export_${LocalDate.now()}.db")
                },
                onImport = {
                    importLauncher.launch(arrayOf("*/*"))
                },
            )

            // ── Section 3: Danger zone ────────────────────────────────────────
            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            SectionHeader("Danger zone", color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            DangerSection(
                isBusy = uiState.isBusy,
                onReset = settingsViewModel::onResetClicked,
            )

            // ── Section 4: Developer (debug builds only) ──────────────────────
            if (BuildConfig.DEBUG) {
                val seederViewModel: SeederViewModel = hiltViewModel()
                val seederState by seederViewModel.state.collectAsStateWithLifecycle()
                Spacer(Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))
                SectionHeader("Developer")
                Spacer(Modifier.height(8.dp))
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

// ── Theme selector ────────────────────────────────────────────────────────────

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
                onClick  = { onSelect(pref) },
                label    = { Text(label) },
            )
        }
    }
}

// ── Backup section ────────────────────────────────────────────────────────────

@Composable
private fun BackupSection(
    isBusy: Boolean,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick  = onExport,
            enabled  = !isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Export database") }

        OutlinedButton(
            onClick  = onImport,
            enabled  = !isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Import database") }
    }
}

// ── Danger zone ───────────────────────────────────────────────────────────────

@Composable
private fun DangerSection(
    isBusy: Boolean,
    onReset: () -> Unit,
) {
    OutlinedButton(
        onClick  = onReset,
        enabled  = !isBusy,
        modifier = Modifier.fillMaxWidth(),
        colors   = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error,
        ),
    ) { Text("Reset database") }
}

// ── Developer / Seeder section ────────────────────────────────────────────────

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
            onClick  = onSeedThirtyDays,
            enabled  = !isRunning,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Seed 30 days of data") }

        OutlinedButton(
            onClick  = onSeedToday,
            enabled  = !isRunning,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Seed today only") }

        OutlinedButton(
            onClick  = onClearData,
            enabled  = !isRunning,
            modifier = Modifier.fillMaxWidth(),
            colors   = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) { Text("Clear seeder data") }

        AnimatedVisibility(visible = isRunning) {
            val progress = (state as? SeederState.Running)?.progress ?: 0f
            Column {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text  = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val statusText = when (state) {
            is SeederState.Idle           -> "Idle"
            is SeederState.Running        -> null  // shown inline via progress above
            is SeederState.Done           -> "Done"
            is SeederState.PartialSuccess -> "Partial: ${state.failedDates.size} day(s) failed"
            is SeederState.Error          -> "Error: ${state.message}"
        }
        if (statusText != null) {
            Text(
                text  = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = when (state) {
                    is SeederState.Error -> MaterialTheme.colorScheme.error
                    is SeederState.Done  -> MaterialTheme.colorScheme.primary
                    else                 -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        if (state is SeederState.Done || state is SeederState.PartialSuccess || state is SeederState.Error) {
            TextButton(onClick = onDismissResult) { Text("Dismiss") }
        }
    }
}
