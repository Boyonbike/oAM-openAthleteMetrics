package com.athletedata.openAthleteMetrics.ui.overview.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.athletedata.openAthleteMetrics.data.model.WidgetLayout
import com.athletedata.openAthleteMetrics.data.model.WidgetSize
import com.athletedata.openAthleteMetrics.data.model.WidgetType
import com.athletedata.openAthleteMetrics.ui.theme.TypographyMeta
import com.athletedata.openAthleteMetrics.ui.theme.TypographyTitle

private data class CatalogueEntry(
    val type: WidgetType,
    val label: String,
    val group: String,
    val defaultSize: WidgetSize,
    val allowWide: Boolean = true,
)

private val ALL_WIDGET_TYPES = listOf(
    CatalogueEntry(WidgetType.Hr,                 "Heart Rate",      "Metrics",  WidgetSize.SMALL),
    CatalogueEntry(WidgetType.Hrv,                "HRV",             "Metrics",  WidgetSize.SMALL),
    CatalogueEntry(WidgetType.Rhr,                "Resting HR",      "Metrics",  WidgetSize.SMALL),
    CatalogueEntry(WidgetType.Sleep,              "Sleep",           "Metrics",  WidgetSize.SMALL),
    CatalogueEntry(WidgetType.Spo2,               "SpO₂",            "Metrics",  WidgetSize.SMALL),
    CatalogueEntry(WidgetType.Steps,              "Steps",           "Metrics",  WidgetSize.SMALL),
    CatalogueEntry(WidgetType.Weight,             "Weight",          "User Input", WidgetSize.WIDE, allowWide = false),
    CatalogueEntry(WidgetType.StarredLifestyleBar,"Lifestyle",       "Bars",     WidgetSize.WIDE, allowWide = false),
    CatalogueEntry(WidgetType.StarredHabitsBar,   "Habits",          "Bars",     WidgetSize.WIDE, allowWide = false),
    CatalogueEntry(WidgetType.Activities,         "Activities",      "Bars",     WidgetSize.WIDE, allowWide = false),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetCatalogueSheet(
    placedWidgets: List<WidgetLayout>,
    onAddWidget: (type: WidgetType, size: WidgetSize) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val placedDiscriminators = remember(placedWidgets) {
        placedWidgets
            .map { WidgetType.discriminator(it.type) }
            .filter { d -> ALL_WIDGET_TYPES.find { WidgetType.discriminator(it.type) == d }?.allowWide == false }
            .toSet()
    }
    val sizeSelections = remember { mutableStateMapOf<String, WidgetSize>() }

    val available = remember(placedDiscriminators) {
        ALL_WIDGET_TYPES.filter { entry ->
            WidgetType.discriminator(entry.type) !in placedDiscriminators
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 36.dp),
        ) {
            Text(
                text = "Add Widget",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            if (available.isEmpty()) {
                Text(
                    text = "All widget types are already on your dashboard.",
                    style = TypographyMeta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val groups = available.groupBy { it.group }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    groups.forEach { (groupName, entries) ->
                        item {
                            Text(
                                text = groupName,
                                style = TypographyTitle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                            )
                        }
                        items(entries) { entry ->
                            val disc = WidgetType.discriminator(entry.type)
                            val selectedSize = sizeSelections[disc] ?: entry.defaultSize
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = entry.label, style = MaterialTheme.typography.bodyMedium)
                                    if (entry.allowWide) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            FilterChip(
                                                selected = selectedSize == WidgetSize.SMALL,
                                                onClick = { sizeSelections[disc] = WidgetSize.SMALL },
                                                label = { Text("Small") },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                                    selectedLabelColor     = MaterialTheme.colorScheme.onPrimary,
                                                ),
                                            )
                                            FilterChip(
                                                selected = selectedSize == WidgetSize.WIDE,
                                                onClick = { sizeSelections[disc] = WidgetSize.WIDE },
                                                label = { Text("Wide") },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                                    selectedLabelColor     = MaterialTheme.colorScheme.onPrimary,
                                                ),
                                            )
                                        }
                                    }
                                }
                                TextButton(onClick = {
                                    onAddWidget(entry.type, selectedSize)
                                    onDismiss()
                                }) {
                                    Text("Add")
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
