package com.athletedata.openAthleteMetrics.ui.overview.widgets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import com.athletedata.openAthleteMetrics.data.model.WidgetDefinition
import com.athletedata.openAthleteMetrics.data.model.WidgetTemplateId
import com.athletedata.openAthleteMetrics.ui.theme.TypographyMeta
import com.athletedata.openAthleteMetrics.ui.theme.TypographyTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetCatalogueSheet(
    placedWidgets: List<WidgetDefinition>,
    onAddWidget: (templateId: WidgetTemplateId, colSpan: Int, rowSpan: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val available = remember(placedWidgets) { availableCatalogue(placedWidgets) }
    var expandedCategories by remember { mutableStateOf<Set<WidgetCatalogueCategory>>(emptySet()) }
    var expandedMetricTypes by remember { mutableStateOf<Set<String>>(emptySet()) }

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
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(available, key = { it.category.name }) { group ->
                        CategoryAccordion(
                            group = group,
                            isExpanded = group.category in expandedCategories,
                            onToggle = {
                                expandedCategories = if (group.category in expandedCategories) {
                                    expandedCategories - group.category
                                } else {
                                    expandedCategories + group.category
                                }
                            },
                            expandedMetricTypes = expandedMetricTypes,
                            onToggleMetricType = { id ->
                                expandedMetricTypes = if (id in expandedMetricTypes) {
                                    expandedMetricTypes - id
                                } else {
                                    expandedMetricTypes + id
                                }
                            },
                            onAddWidget = { templateId, colSpan, rowSpan ->
                                onAddWidget(templateId, colSpan, rowSpan)
                                onDismiss()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryAccordion(
    group: WidgetCatalogueCategoryGroup,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    expandedMetricTypes: Set<String>,
    onToggleMetricType: (String) -> Unit,
    onAddWidget: (templateId: WidgetTemplateId, colSpan: Int, rowSpan: Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        val chevronRotation by animateFloatAsState(
            targetValue = if (isExpanded) 180f else 0f,
            label = "chevron_${group.category.name}",
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(group.category.displayName, style = TypographyTitle)
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(chevronRotation),
            )
        }
        AnimatedVisibility(visible = isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, bottom = 8.dp),
            ) {
                group.metricTypes.forEach { metricType ->
                    MetricTypeAccordion(
                        metricType = metricType,
                        isExpanded = metricType.id in expandedMetricTypes,
                        onToggle = { onToggleMetricType(metricType.id) },
                        onAddWidget = onAddWidget,
                    )
                }
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun MetricTypeAccordion(
    metricType: WidgetCatalogueMetricType,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onAddWidget: (templateId: WidgetTemplateId, colSpan: Int, rowSpan: Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        val chevronRotation by animateFloatAsState(
            targetValue = if (isExpanded) 180f else 0f,
            label = "chevron_${metricType.id}",
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(metricType.label, style = MaterialTheme.typography.bodyMedium)
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .rotate(chevronRotation)
                    .padding(end = 4.dp),
            )
        }
        AnimatedVisibility(visible = isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, bottom = 4.dp),
            ) {
                metricType.widgets.forEach { entry ->
                    WidgetListingRow(entry = entry, onAddWidget = onAddWidget)
                }
            }
        }
    }
}

@Composable
private fun WidgetListingRow(
    entry: WidgetCatalogueEntry,
    onAddWidget: (templateId: WidgetTemplateId, colSpan: Int, rowSpan: Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = entry.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = entry.typeLabel,
                style = TypographyMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { onAddWidget(entry.templateId, entry.defaultColSpan, entry.defaultRowSpan) }) {
            Text("Add")
        }
    }
    HorizontalDivider()
}
