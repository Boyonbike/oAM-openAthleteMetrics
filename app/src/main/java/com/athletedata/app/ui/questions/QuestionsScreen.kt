package com.athletedata.app.ui.questions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.athletedata.app.data.model.QuestionCategory
import com.athletedata.app.data.model.QuestionType
import com.athletedata.app.ui.nav.AppTopBar
import com.athletedata.app.ui.nav.Page
import com.athletedata.app.ui.theme.TypographyMeta
import com.athletedata.app.ui.theme.TypographyTitle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestionsScreen(
    onNavigate: (Page) -> Unit,
    onSettingsClick: () -> Unit,
    onDevicesClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: QuestionsViewModel = hiltViewModel(),
) {
    val lifestyleItems by viewModel.lifestyleItems.collectAsStateWithLifecycle()
    val customItems by viewModel.customItems.collectAsStateWithLifecycle()
    val editMode by viewModel.editMode.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by remember { mutableStateOf(Tab.LIFESTYLE) }
    // null = sheet closed; non-null = editing that item (create uses a sentinel with id = -1)
    var showAddSheet by remember { mutableStateOf(false) }
    var editingItem by remember { mutableStateOf<QuestionItem?>(null) }

    LaunchedEffect(Unit) {
        viewModel.errors.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AppTopBar(
                currentPage = Page.QUESTIONS,
                onSettingsClick = onSettingsClick,
                onDevicesClick = onDevicesClick,
                onNavigate = onNavigate,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // ── Tab selector with inline action icons ─────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                TabSelector(
                    selectedTab = selectedTab,
                    onSelect = {
                        selectedTab = it
                        if (editMode) viewModel.exitEditMode()
                    },
                )
                Row(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (editMode) {
                        TextButton(
                            onClick = viewModel::exitEditMode,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Text("Done", style = TypographyMeta)
                        }
                    } else {
                        if (selectedTab == Tab.HABITS) {
                            IconButton(
                                onClick = { showAddSheet = true },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Add,
                                    contentDescription = "Add habit",
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        IconButton(
                            onClick = viewModel::enterEditMode,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Edit,
                                contentDescription = "Edit",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            // ── Tab content ───────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                when (selectedTab) {
                    Tab.LIFESTYLE -> {
                        val displayItems = if (editMode) lifestyleItems
                                           else lifestyleItems.filter { it.definition.isVisible }
                        val starredCount = lifestyleItems.count { it.definition.isStarred }
                        displayItems.forEachIndexed { index, item ->
                            QuestionRow(
                                item = item,
                                onScaleSelect = { value ->
                                    if (value.toString() == item.currentValue) {
                                        viewModel.clearResponse(item.definition.id)
                                    } else {
                                        viewModel.saveResponse(item.definition.id, value.toString())
                                    }
                                },
                                editMode = editMode,
                                modifier = if (editMode && !item.definition.isVisible)
                                               Modifier.alpha(0.4f)
                                           else Modifier,
                                editControls = {
                                    // Eye: normal eye = visible, strikethrough eye = hidden
                                    IconButton(
                                        onClick = { viewModel.toggleVisibility(item.definition.id) },
                                    ) {
                                        Icon(
                                            imageVector = if (item.definition.isVisible)
                                                              Icons.Outlined.Visibility
                                                          else Icons.Outlined.VisibilityOff,
                                            contentDescription = if (item.definition.isVisible)
                                                                      "Hide question"
                                                                  else "Show question",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    // Star: adds this question to the dashboard lifestyle bar (max 5)
                                    IconButton(
                                        onClick = { viewModel.toggleStar(item.definition.id) },
                                        enabled = item.definition.isVisible &&
                                            (item.definition.isStarred || starredCount < 5),
                                    ) {
                                        Icon(
                                            imageVector = if (item.definition.isStarred)
                                                              Icons.Filled.Star
                                                          else Icons.Outlined.Star,
                                            contentDescription = if (item.definition.isStarred)
                                                                      "Unstar from dashboard"
                                                                  else "Star for dashboard",
                                            tint = if (item.definition.isStarred)
                                                       MaterialTheme.colorScheme.primary
                                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    ReorderButtons(
                                        canMoveUp = index > 0,
                                        canMoveDown = index < displayItems.lastIndex,
                                        onMoveUp = {
                                            viewModel.reorder(QuestionCategory.LIFESTYLE, index, index - 1)
                                        },
                                        onMoveDown = {
                                            viewModel.reorder(QuestionCategory.LIFESTYLE, index, index + 1)
                                        },
                                    )
                                },
                            )
                            HorizontalDivider()
                        }
                    }

                    Tab.HABITS -> {
                        val displayItems = if (editMode) customItems
                                           else customItems.filter { it.definition.isVisible }
                        displayItems.forEachIndexed { index, item ->
                            QuestionRow(
                                item = item,
                                onScaleSelect = { value ->
                                    if (value.toString() == item.currentValue) {
                                        viewModel.clearResponse(item.definition.id)
                                    } else {
                                        viewModel.saveResponse(item.definition.id, value.toString())
                                    }
                                },
                                onBooleanToggle = { value ->
                                    if (value == item.currentValue) {
                                        viewModel.clearResponse(item.definition.id)
                                    } else {
                                        viewModel.saveResponse(item.definition.id, value)
                                    }
                                },
                                onTextSave = { value ->
                                    viewModel.saveResponse(item.definition.id, value)
                                },
                                editMode = editMode,
                                modifier = if (editMode && !item.definition.isVisible)
                                               Modifier.alpha(0.4f)
                                           else Modifier,
                                editControls = {
                                    // Delete
                                    IconButton(
                                        onClick = { viewModel.deleteCustomQuestion(item.definition.id) },
                                    ) {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            contentDescription = "Delete habit",
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                    // Edit (open pre-populated sheet, stay in edit mode)
                                    IconButton(onClick = { editingItem = item }) {
                                        Icon(
                                            Icons.Outlined.Edit,
                                            contentDescription = "Edit habit",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    // Eye: normal eye = visible, strikethrough = hidden
                                    IconButton(
                                        onClick = { viewModel.toggleVisibility(item.definition.id) },
                                    ) {
                                        Icon(
                                            imageVector = if (item.definition.isVisible)
                                                              Icons.Outlined.Visibility
                                                          else Icons.Outlined.VisibilityOff,
                                            contentDescription = if (item.definition.isVisible)
                                                                      "Hide habit"
                                                                  else "Show habit",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    ReorderButtons(
                                        canMoveUp = index > 0,
                                        canMoveDown = index < displayItems.lastIndex,
                                        onMoveUp = {
                                            viewModel.reorder(QuestionCategory.CUSTOM, index, index - 1)
                                        },
                                        onMoveDown = {
                                            viewModel.reorder(QuestionCategory.CUSTOM, index, index + 1)
                                        },
                                    )
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // ── Create new habit ──────────────────────────────────────────────────────
    if (showAddSheet) {
        HabitSheet(
            title = "Add habit",
            initialName = "",
            initialType = QuestionType.SCALE,
            onSave = { name, type ->
                viewModel.addCustomQuestion(name, type)
                showAddSheet = false
            },
            onDismiss = { showAddSheet = false },
        )
    }

    // ── Edit existing habit (stays in edit mode after save) ───────────────────
    editingItem?.let { item ->
        HabitSheet(
            title = "Edit habit",
            initialName = item.definition.name,
            initialType = item.definition.type,
            onSave = { name, type ->
                viewModel.editCustomQuestion(item.definition.id, name, type)
                editingItem = null   // close sheet; editMode remains true
            },
            onDismiss = { editingItem = null },
        )
    }
}

// ── Tab enum + centered selector ──────────────────────────────────────────────

private enum class Tab(val label: String) {
    LIFESTYLE("Lifestyle"),
    HABITS("Habits"),
}

@Composable
private fun TabSelector(
    selectedTab: Tab,
    onSelect: (Tab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Tab.entries.forEach { tab ->
            val selected = tab == selectedTab
            TextButton(
                onClick = { onSelect(tab) },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (selected) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Text(
                    text = tab.label,
                    style = if (selected) MaterialTheme.typography.labelLarge else TypographyMeta,
                )
            }
        }
    }
}

// ── Question row ──────────────────────────────────────────────────────────────

@Composable
private fun QuestionRow(
    item: QuestionItem,
    onScaleSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onBooleanToggle: ((String) -> Unit)? = null,
    onTextSave: ((String) -> Unit)? = null,
    editMode: Boolean = false,
    editControls: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.definition.name,
                style = TypographyTitle,
                modifier = Modifier.weight(1f),
            )
            if (editMode && editControls != null) {
                editControls()
            }
        }

        if (!editMode) {
            Spacer(Modifier.height(8.dp))
            when (item.definition.type) {
                QuestionType.SCALE -> ScaleChips(
                    current = item.currentValue?.toIntOrNull(),
                    onSelect = onScaleSelect,
                )
                QuestionType.BOOLEAN -> BooleanChips(
                    current = item.currentValue,
                    onSelect = { onBooleanToggle?.invoke(it) },
                )
                QuestionType.TEXT -> TextInput(
                    current = item.currentValue ?: "",
                    onSave = { onTextSave?.invoke(it) },
                )
            }
        }
    }
}

// ── Scale chips 1–5 ───────────────────────────────────────────────────────────

@Composable
private fun ScaleChips(
    current: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (n in 1..5) {
            FilterChip(
                selected = current == n,
                onClick = { onSelect(n) },
                label = { Text(n.toString()) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        }
    }
}

// ── Boolean chips ─────────────────────────────────────────────────────────────

@Composable
private fun BooleanChips(
    current: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = current == "1",
            onClick = { onSelect("1") },
            label = { Text("Yes") },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primary,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            ),
        )
        FilterChip(
            selected = current == "0",
            onClick = { onSelect("0") },
            label = { Text("No") },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primary,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            ),
        )
    }
}

// ── Text input ────────────────────────────────────────────────────────────────

@Composable
private fun TextInput(
    current: String,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(current) { mutableStateOf(current) }
    val focusManager = LocalFocusManager.current

    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(
            onDone = {
                focusManager.clearFocus()
                if (text.isNotBlank()) onSave(text)
            },
        ),
        modifier = modifier
            .fillMaxWidth()
            .imePadding(),
    )
}

// ── Reorder up/down buttons ───────────────────────────────────────────────────

@Composable
private fun ReorderButtons(
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    IconButton(onClick = onMoveUp, enabled = canMoveUp) {
        Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "Move up")
    }
    IconButton(onClick = onMoveDown, enabled = canMoveDown) {
        Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Move down")
    }
}

// ── Shared habit sheet (used for both create and edit) ────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HabitSheet(
    title: String,
    initialName: String,
    initialType: QuestionType,
    onSave: (name: String, type: QuestionType) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var selectedType by remember { mutableStateOf(initialType) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 36.dp)
                .imePadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close")
                }
            }

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Habit name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))

            Text("Type", style = TypographyMeta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    QuestionType.BOOLEAN to "Toggle",
                    QuestionType.SCALE   to "Scale",
                    QuestionType.TEXT    to "Text",
                ).forEach { (type, label) ->
                    FilterChip(
                        selected = selectedType == type,
                        onClick = { selectedType = type },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            TextButton(
                onClick = {
                    if (name.isNotBlank()) {
                        scope.launch {
                            sheetState.hide()
                            onSave(name.trim(), selectedType)
                        }
                    }
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Save")
            }
        }
    }
}
