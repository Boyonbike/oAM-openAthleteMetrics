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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.athletedata.openAthleteMetrics.ui.components.PillSelector
import com.athletedata.openAthleteMetrics.ui.components.SectionHeader
import com.athletedata.openAthleteMetrics.ui.questions.DailyQuestionsViewModel
import com.athletedata.openAthleteMetrics.ui.questions.WeightEntrySheet
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

    val profileViewModel: UserProfileViewModel = hiltViewModel()
    val profile by profileViewModel.profile.collectAsStateWithLifecycle()

    // DailyQuestionsViewModel scoped to this screen so WeightEntrySheet is not
    // re-created on each open/close of the sheet.
    val dailyQuestionsViewModel: DailyQuestionsViewModel = hiltViewModel()
    var showWeightSheet by remember { mutableStateOf(false) }

    var selectedTab by remember { mutableStateOf(0) }

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

    // ── RESET-SYSTEM: Multi-select delete sheet ───────────────────────────────
    if (uiState.showResetSheet) {
        ModalBottomSheet(
            onDismissRequest = settingsViewModel::dismissResetSheet,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            ResetSheetContent(
                uiState = uiState,
                onToggleMetrics = settingsViewModel::toggleMetrics,
                onToggleProfile = settingsViewModel::toggleProfile,
                onToggleDevices = settingsViewModel::toggleDevices,
                onCancel = settingsViewModel::dismissResetSheet,
                onDeleteSelected = settingsViewModel::onDeleteSelectedClicked,
            )
        }
    }

    // ── RESET-SYSTEM: Confirm deletion dialog ─────────────────────────────────
    if (uiState.showResetConfirmDialog) {
        val confirmBodyText = buildString {
            if (uiState.metricsSelected) append(
                "This will permanently delete all metric readings, sleep sessions, questions, habits, and weight logs. This cannot be undone."
            )
            if (uiState.profileSelected) {
                if (isNotEmpty()) append("\n\n")
                append("This will permanently delete your user profile. This cannot be undone.")
            }
            if (uiState.devicesSelected) {
                if (isNotEmpty()) append("\n\n")
                append("This will remove all paired devices and loaded drivers. This cannot be undone.")
            }
        }
        AlertDialog(
            onDismissRequest = settingsViewModel::dismissResetConfirmDialog,
            title = { Text("Confirm deletion") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(confirmBodyText)
                    if (uiState.isBusy) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    OutlinedTextField(
                        value = uiState.resetConfirmText,
                        onValueChange = settingsViewModel::onResetConfirmTextChanged,
                        placeholder = { Text("Type DELETE") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.isBusy,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = settingsViewModel::executeReset,
                    enabled = uiState.resetConfirmText == "DELETE" && !uiState.isBusy,
                ) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(
                    onClick = settingsViewModel::dismissResetConfirmDialog,
                    enabled = !uiState.isBusy,
                ) { Text("Cancel") }
            },
        )
    }

    // WeightEntryBottomSheet is reused here rather than an inline editor because
    // weight writes to a DailyContext entry (not directly to UserProfile) and
    // includes body fat % and notes — reusing the canonical entry form.
    if (showWeightSheet) {
        WeightEntrySheet(
            viewModel = dailyQuestionsViewModel,
            onDismiss = { showWeightSheet = false },
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    PillSelector(
                        tabs = listOf("Profile", "App"),
                        selectedIndex = selectedTab,
                        onSelect = { selectedTab = it },
                    )
                }
            }
        },
    ) { innerPadding ->
        when (selectedTab) {
            0 -> ProfileTab(
                profile = profile,
                onUpdate = profileViewModel::updateProfile,
                onWeightTap = { showWeightSheet = true },
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
            )
            else -> AppTab(
                theme = theme,
                onSelectTheme = settingsViewModel::setTheme,
                isBusy = uiState.isBusy,
                onExport = { exportLauncher.launch("athlete_data_export_${LocalDate.now()}.db") },
                onImport = { importLauncher.launch(arrayOf("*/*")) },
                onReset = settingsViewModel::openResetSheet,
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
            )
        }
    }
}

// ── App tab (existing settings content) ──────────────────────────────────────

@Composable
private fun AppTab(
    theme: ThemePreference,
    onSelectTheme: (ThemePreference) -> Unit,
    isBusy: Boolean,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .padding(bottom = 80.dp),
    ) {
        // ── Section: Settings ─────────────────────────────────────────────────
        SectionHeader("Settings")
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppearanceTile(theme = theme, onSelect = onSelectTheme)
            BackupTile(isBusy = isBusy, onExport = onExport, onImport = onImport)
            DangerTile(isBusy = isBusy, onReset = onReset)
        }

        // ── Section: Experimental ─────────────────────────────────────────────
        Spacer(Modifier.height(24.dp))
        SectionHeader("Experimental")

        // ── Section: Developer (debug builds only) ────────────────────────────
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

// RESET-SYSTEM
@Composable
private fun DangerSection(
    isBusy: Boolean,
    onReset: () -> Unit,
) {
    OutlinedButton(
        onClick  = onReset,
        enabled  = !isBusy,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Reset / Delete Data", color = MaterialTheme.colorScheme.error) }
}

// RESET-SYSTEM
@Composable
private fun ResetSheetContent(
    uiState: SettingsUiState,
    onToggleMetrics: () -> Unit,
    onToggleProfile: () -> Unit,
    onToggleDevices: () -> Unit,
    onCancel: () -> Unit,
    onDeleteSelected: () -> Unit,
) {
    val anySelected = uiState.metricsSelected || uiState.profileSelected || uiState.devicesSelected
    Column(
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Delete Data", style = MaterialTheme.typography.titleLarge)
        ResetOptionRow(
            checked = uiState.metricsSelected,
            onToggle = onToggleMetrics,
            title = "Metrics & Questions database",
            description = "Deletes all metric readings, sleep sessions, daily summaries, daily context (questions, habits, weight logs), and computed baselines. Your profile is not affected.",
        )
        ResetOptionRow(
            checked = uiState.profileSelected,
            onToggle = onToggleProfile,
            title = "User Profile",
            description = "Deletes your name, date of birth, body metrics, HR zones, and all profile data. Does not affect recorded metrics.",
        )
        ResetOptionRow(
            checked = uiState.devicesSelected,
            onToggle = onToggleDevices,
            title = "Devices & Drivers",
            description = "Removes all paired devices and loaded JSON drivers. Removes device entries from the UI. Does not affect recorded data or profile.",
        )
        if (uiState.isBusy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            TextButton(onClick = onCancel, enabled = !uiState.isBusy) { Text("Cancel") }
            Button(
                onClick = onDeleteSelected,
                enabled = anySelected && !uiState.isBusy,
            ) { Text("Delete Selected →") }
        }
    }
}

// RESET-SYSTEM
@Composable
private fun ResetOptionRow(
    checked: Boolean,
    onToggle: () -> Unit,
    title: String,
    description: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
