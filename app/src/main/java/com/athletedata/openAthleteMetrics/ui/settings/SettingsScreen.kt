package com.athletedata.openAthleteMetrics.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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

    LaunchedEffect(Unit) {
        settingsViewModel.effects.collect { effect ->
            when (effect) {
                is SettingsEffect.ShowSnackbar -> snackbarHostState.showSnackbar(effect.message)
                is SettingsEffect.NavigateToDashboard -> onNavigateToDashboard()
            }
        }
    }

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
            Column {
                TopAppBar(
                    title = { Text("Settings", style = MaterialTheme.typography.titleMedium) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
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
            // ── Section: Settings ─────────────────────────────────────────────
            SectionHeader("Settings")
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppearanceTile(
                    theme = theme,
                    onSelect = settingsViewModel::setTheme,
                )
                BackupTile(
                    isBusy = uiState.isBusy,
                    onExport = { exportLauncher.launch("athlete_data_export_${LocalDate.now()}.db") },
                    onImport = { importLauncher.launch(arrayOf("*/*")) },
                )
                DangerTile(
                    isBusy = uiState.isBusy,
                    onReset = settingsViewModel::onResetClicked,
                )
            }

            // ── Section: Experimental ─────────────────────────────────────────
            Spacer(Modifier.height(24.dp))
            SectionHeader("Experimental")
            // No tiles yet.

            // ── Section: Developer (debug builds only) ────────────────────────
            if (BuildConfig.DEBUG) {
                val seederViewModel: SeederViewModel = hiltViewModel()
                val seederState by seederViewModel.state.collectAsStateWithLifecycle()
                Spacer(Modifier.height(24.dp))
                SectionHeader("Developer")
                Spacer(Modifier.height(8.dp))
                SeederTile(
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

// ── Generic tile shell ────────────────────────────────────────────────────────

@Composable
private fun SettingsCategoryTile(
    title: String,
    titleColor: Color = Color.Unspecified,
    collapsedSummary: @Composable () -> Unit,
    expandedContent: @Composable ColumnScope.() -> Unit,
) {
    var isExpanded by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "chevron_$title",
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = titleColor,
                )
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.graphicsLayer { rotationZ = chevronRotation },
                )
            }
            AnimatedVisibility(visible = !isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 10.dp),
                ) {
                    collapsedSummary()
                }
            }
            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    expandedContent()
                }
            }
        }
    }
}

// ── Appearance tile ───────────────────────────────────────────────────────────

@Composable
private fun AppearanceTile(
    theme: ThemePreference,
    onSelect: (ThemePreference) -> Unit,
) {
    SettingsCategoryTile(
        title = "Appearance",
        collapsedSummary = {
            Text(
                text = when (theme) {
                    ThemePreference.LIGHT  -> "Light"
                    ThemePreference.DARK   -> "Dark"
                    ThemePreference.SYSTEM -> "System"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        expandedContent = {
            ThemeSelector(current = theme, onSelect = onSelect)
        },
    )
}

// ── Backup tile ───────────────────────────────────────────────────────────────

@Composable
private fun BackupTile(
    isBusy: Boolean,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    SettingsCategoryTile(
        title = "Backup",
        collapsedSummary = {
            Text(
                text = "Export or import your database",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        expandedContent = {
            BackupSection(isBusy = isBusy, onExport = onExport, onImport = onImport)
        },
    )
}

// ── Danger tile ───────────────────────────────────────────────────────────────

@Composable
private fun DangerTile(
    isBusy: Boolean,
    onReset: () -> Unit,
) {
    SettingsCategoryTile(
        title = "Danger zone",
        titleColor = MaterialTheme.colorScheme.error,
        collapsedSummary = {
            Text(
                text = "Reset all app data",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        expandedContent = {
            DangerSection(isBusy = isBusy, onReset = onReset)
        },
    )
}

// ── Seeder tile ───────────────────────────────────────────────────────────────

@Composable
private fun SeederTile(
    state: SeederState,
    onSeedThirtyDays: () -> Unit,
    onSeedToday: () -> Unit,
    onClearData: () -> Unit,
    onDismissResult: () -> Unit,
) {
    val collapsedLabel = when (state) {
        is SeederState.Idle           -> "Idle"
        is SeederState.Running        -> "${(state.progress * 100).toInt()}% complete"
        is SeederState.Done           -> "Done"
        is SeederState.PartialSuccess -> "Partial: ${state.failedDates.size} day(s) failed"
        is SeederState.Error          -> "Error: ${state.message}"
    }
    SettingsCategoryTile(
        title = "Seeder",
        collapsedSummary = {
            Text(
                text = collapsedLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        expandedContent = {
            SeederSection(
                state = state,
                onSeedThirtyDays = onSeedThirtyDays,
                onSeedToday = onSeedToday,
                onClearData = onClearData,
                onDismissResult = onDismissResult,
            )
        },
    )
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
            is SeederState.Running        -> null
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
