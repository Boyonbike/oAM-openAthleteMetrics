package com.athletedata.openAthleteMetrics.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.athletedata.openAthleteMetrics.data.model.HrZone
import com.athletedata.openAthleteMetrics.data.model.UserProfile
import com.athletedata.openAthleteMetrics.ui.components.SectionHeader
import java.time.Instant
import java.time.ZoneOffset

private enum class ProfileField {
    NAME, HEIGHT, STRIDE_LENGTH, WRIST, RMR, VO2_MAX, MAX_HR
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileTab(
    profile: UserProfile?,
    onUpdate: (UserProfile) -> Unit,
    onWeightTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // ── Inline editing state ──────────────────────────────────────────────────
    var editingField by remember { mutableStateOf<ProfileField?>(null) }

    // Per-field drafts so focus-loss on one field always saves the right value
    // even when the user taps directly to another field.
    var nameDraft by remember { mutableStateOf("") }
    var heightDraft by remember { mutableStateOf("") }
    var strideDraft by remember { mutableStateOf("") }
    var wristDraft by remember { mutableStateOf("") }
    var rmrDraft by remember { mutableStateOf("") }
    var vo2Draft by remember { mutableStateOf("") }
    var maxHrDraft by remember { mutableStateOf("") }

    var editingZone by remember { mutableStateOf<Int?>(null) }
    var zoneUpperHasFocused by remember { mutableStateOf(false) }
    var zoneLowerDraft by remember { mutableStateOf("") }
    var zoneUpperDraft by remember { mutableStateOf("") }

    var showDatePicker by remember { mutableStateOf(false) }

    // Single FocusRequester shared across all single-field rows (only one active at a time).
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(editingField) {
        if (editingField != null) {
            try { focusRequester.requestFocus() } catch (_: IllegalStateException) {}
        }
    }

    val zoneLowerFocus = remember { FocusRequester() }
    val zoneUpperFocus = remember { FocusRequester() }
    LaunchedEffect(editingZone) {
        zoneUpperHasFocused = false
        if (editingZone != null) {
            try { zoneLowerFocus.requestFocus() } catch (_: IllegalStateException) {}
        }
    }

    // ── Save helpers ──────────────────────────────────────────────────────────

    fun saveField(field: ProfileField) {
        val current = profile ?: UserProfile()
        onUpdate(
            when (field) {
                ProfileField.NAME          -> current.copy(name = nameDraft.ifBlank { null })
                ProfileField.HEIGHT        -> current.copy(heightCm = heightDraft.toDoubleOrNull())
                ProfileField.STRIDE_LENGTH -> current.copy(strideLengthCm = strideDraft.toDoubleOrNull())
                ProfileField.WRIST         -> current.copy(wristCircumferenceMm = wristDraft.toDoubleOrNull())
                ProfileField.RMR           -> current.copy(restingMetabolicRate = rmrDraft.toDoubleOrNull())
                ProfileField.VO2_MAX       -> current.copy(vo2Max = vo2Draft.toDoubleOrNull())
                ProfileField.MAX_HR        -> current.copy(maxHr = maxHrDraft.toIntOrNull())
            }
        )
        if (editingField == field) editingField = null
    }

    fun saveZone() {
        val zone = editingZone ?: return
        editingZone = null
        val lower = zoneLowerDraft.toIntOrNull()
        val upper = zoneUpperDraft.toIntOrNull()
        if (lower != null && upper != null) {
            val current = profile ?: UserProfile()
            val updatedZones = current.hrZones.toMutableList().apply {
                removeIf { it.zone == zone }
                add(HrZone(zone = zone, lowerBpm = lower, upperBpm = upper))
                sortBy { it.zone }
            }
            onUpdate(current.copy(hrZones = updatedZones))
        }
    }

    // ── Date picker dialog ────────────────────────────────────────────────────
    if (showDatePicker) {
        val initialMillis = profile?.dateOfBirth?.let {
            runCatching {
                java.time.LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            }.getOrNull()
        }
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        onUpdate((profile ?: UserProfile()).copy(dateOfBirth = date.toString()))
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // ── Content ───────────────────────────────────────────────────────────────
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .padding(bottom = 80.dp),
    ) {

        // ── Section: Identity ─────────────────────────────────────────────────
        SectionHeader("Identity")
        Spacer(Modifier.height(8.dp))

        EditableFieldRow(
            label = "Name",
            unit = null,
            displayValue = profile?.name,
            isEditing = editingField == ProfileField.NAME,
            draft = nameDraft,
            onDraftChange = { nameDraft = it },
            keyboardType = KeyboardType.Text,
            focusRequester = focusRequester,
            onTap = {
                nameDraft = profile?.name ?: ""
                editingField = ProfileField.NAME
            },
            onSave = { saveField(ProfileField.NAME) },
        )
        HorizontalDivider()

        // Date of Birth — tap opens DatePickerDialog
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showDatePicker = true }
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Date of Birth", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = profile?.dateOfBirth ?: "—",
                style = MaterialTheme.typography.bodyMedium,
                color = if (profile?.dateOfBirth != null) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()

        // Biological Sex — inline chip selector, saves immediately on tap
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            Text("Biological Sex", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("MALE" to "Male", "FEMALE" to "Female", "OTHER" to "Other").forEach { (key, label) ->
                    FilterChip(
                        selected = profile?.biologicalSex == key,
                        onClick = { onUpdate((profile ?: UserProfile()).copy(biologicalSex = key)) },
                        label = { Text(label) },
                    )
                }
            }
        }

        // ── Section: Body Metrics ─────────────────────────────────────────────
        Spacer(Modifier.height(16.dp))
        SectionHeader("Body Metrics")
        Spacer(Modifier.height(8.dp))

        EditableFieldRow(
            label = "Height",
            unit = "cm",
            displayValue = profile?.heightCm?.let { "%.1f".format(it) },
            isEditing = editingField == ProfileField.HEIGHT,
            draft = heightDraft,
            onDraftChange = { heightDraft = it },
            keyboardType = KeyboardType.Decimal,
            focusRequester = focusRequester,
            onTap = {
                heightDraft = profile?.heightCm?.let { "%.1f".format(it) } ?: ""
                editingField = ProfileField.HEIGHT
            },
            onSave = { saveField(ProfileField.HEIGHT) },
        )
        HorizontalDivider()

        EditableFieldRow(
            label = "Stride Length",
            unit = "cm",
            displayValue = profile?.strideLengthCm?.let { "%.1f".format(it) },
            isEditing = editingField == ProfileField.STRIDE_LENGTH,
            draft = strideDraft,
            onDraftChange = { strideDraft = it },
            keyboardType = KeyboardType.Decimal,
            focusRequester = focusRequester,
            onTap = {
                strideDraft = profile?.strideLengthCm?.let { "%.1f".format(it) } ?: ""
                editingField = ProfileField.STRIDE_LENGTH
            },
            onSave = { saveField(ProfileField.STRIDE_LENGTH) },
        )
        HorizontalDivider()

        EditableFieldRow(
            label = "Wrist Circumference",
            unit = "mm",
            displayValue = profile?.wristCircumferenceMm?.let { "%.1f".format(it) },
            isEditing = editingField == ProfileField.WRIST,
            draft = wristDraft,
            onDraftChange = { wristDraft = it },
            keyboardType = KeyboardType.Decimal,
            focusRequester = focusRequester,
            onTap = {
                wristDraft = profile?.wristCircumferenceMm?.let { "%.1f".format(it) } ?: ""
                editingField = ProfileField.WRIST
            },
            onSave = { saveField(ProfileField.WRIST) },
        )
        HorizontalDivider()

        EditableFieldRow(
            label = "Resting Metabolic Rate",
            unit = "kcal",
            displayValue = profile?.restingMetabolicRate?.let { "%.0f".format(it) },
            isEditing = editingField == ProfileField.RMR,
            draft = rmrDraft,
            onDraftChange = { rmrDraft = it },
            keyboardType = KeyboardType.Decimal,
            focusRequester = focusRequester,
            onTap = {
                rmrDraft = profile?.restingMetabolicRate?.let { "%.0f".format(it) } ?: ""
                editingField = ProfileField.RMR
            },
            onSave = { saveField(ProfileField.RMR) },
        )
        HorizontalDivider()

        EditableFieldRow(
            label = "VO₂ Max",
            unit = "ml/kg/min",
            displayValue = profile?.vo2Max?.let { "%.1f".format(it) },
            isEditing = editingField == ProfileField.VO2_MAX,
            draft = vo2Draft,
            onDraftChange = { vo2Draft = it },
            keyboardType = KeyboardType.Decimal,
            focusRequester = focusRequester,
            onTap = {
                vo2Draft = profile?.vo2Max?.let { "%.1f".format(it) } ?: ""
                editingField = ProfileField.VO2_MAX
            },
            onSave = { saveField(ProfileField.VO2_MAX) },
        )
        HorizontalDivider()

        EditableFieldRow(
            label = "Max HR",
            unit = "bpm",
            displayValue = profile?.maxHr?.toString(),
            isEditing = editingField == ProfileField.MAX_HR,
            draft = maxHrDraft,
            onDraftChange = { maxHrDraft = it },
            keyboardType = KeyboardType.Number,
            focusRequester = focusRequester,
            onTap = {
                maxHrDraft = profile?.maxHr?.toString() ?: ""
                editingField = ProfileField.MAX_HR
            },
            onSave = { saveField(ProfileField.MAX_HR) },
        )
        HorizontalDivider()

        // Weight — read-only, opens WeightEntryBottomSheet on tap
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onWeightTap)
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Weight", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "Auto-synced from daily log",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = profile?.weightKg?.let { "${"%.1f".format(it)} kg" } ?: "—",
                style = MaterialTheme.typography.bodyMedium,
                color = if (profile?.weightKg != null) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ── Section: HR Zones ─────────────────────────────────────────────────
        Spacer(Modifier.height(16.dp))
        SectionHeader("HR Zones")
        Spacer(Modifier.height(8.dp))

        (1..5).forEach { zone ->
            val hrZone = profile?.hrZones?.find { it.zone == zone }

            if (zone > 1) HorizontalDivider()

            if (editingZone == zone) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Zone $zone",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(52.dp),
                    )
                    OutlinedTextField(
                        value = zoneLowerDraft,
                        onValueChange = { zoneLowerDraft = it },
                        label = { Text("Lower") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next,
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { zoneUpperFocus.requestFocus() },
                        ),
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(zoneLowerFocus),
                    )
                    Text("–", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = zoneUpperDraft,
                        onValueChange = { zoneUpperDraft = it },
                        label = { Text("Upper") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { saveZone() }),
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(zoneUpperFocus)
                            .onFocusChanged { fs ->
                                if (fs.isFocused) zoneUpperHasFocused = true
                                else if (zoneUpperHasFocused) saveZone()
                            },
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            editingZone = zone
                            zoneLowerDraft = hrZone?.lowerBpm?.toString() ?: ""
                            zoneUpperDraft = hrZone?.upperBpm?.toString() ?: ""
                        }
                        .padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Zone $zone", style = MaterialTheme.typography.bodyMedium)
                    val lower = hrZone?.lowerBpm?.toString() ?: "—"
                    val upper = hrZone?.upperBpm?.toString() ?: "—"
                    Text(
                        text = "$lower – $upper bpm",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (hrZone != null) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ── Private helper composable ─────────────────────────────────────────────────

@Composable
private fun EditableFieldRow(
    label: String,
    unit: String?,
    displayValue: String?,
    isEditing: Boolean,
    draft: String,
    onDraftChange: (String) -> Unit,
    keyboardType: KeyboardType,
    focusRequester: FocusRequester,
    onTap: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var hasFocused by remember { mutableStateOf(false) }
    LaunchedEffect(isEditing) {
        if (!isEditing) hasFocused = false
    }

    if (isEditing) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            label = { Text(if (unit != null) "$label ($unit)" else label) },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSave() }),
            singleLine = true,
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { fs ->
                    if (fs.isFocused) hasFocused = true
                    else if (hasFocused) onSave()
                },
        )
    } else {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .clickable(onClick = onTap)
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            val valueText = displayValue?.let { v ->
                if (unit != null) "$v $unit" else v
            } ?: "—"
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (displayValue != null) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
