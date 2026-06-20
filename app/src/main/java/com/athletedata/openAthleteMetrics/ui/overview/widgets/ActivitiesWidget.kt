package com.athletedata.openAthleteMetrics.ui.overview.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.athletedata.openAthleteMetrics.data.model.Activity
import com.athletedata.openAthleteMetrics.data.model.UserCategory
import com.athletedata.openAthleteMetrics.data.model.WidgetSize
import com.athletedata.openAthleteMetrics.data.repository.ActivityRepository
import com.athletedata.openAthleteMetrics.ui.theme.TypographyMeta
import com.athletedata.openAthleteMetrics.ui.theme.TypographyTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class ActivitiesWidgetViewModel @Inject constructor(
    private val activityRepo: ActivityRepository,
) : ViewModel() {

    private val _date = MutableStateFlow(LocalDate.now())

    fun setDate(date: LocalDate) { _date.value = date }

    val activities: StateFlow<List<Activity>> = _date
        .flatMapLatest { activityRepo.getActivitiesForDate(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@Composable
fun ActivitiesWidget(
    widgetId: Long,
    date: LocalDate,
    size: WidgetSize,
    isEditMode: Boolean,
    wiggleAngle: Float,
    onTap: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ActivitiesWidgetViewModel = hiltViewModel(key = "activities_widget_$widgetId"),
) {
    LaunchedEffect(date) { viewModel.setDate(date) }
    val activities by viewModel.activities.collectAsStateWithLifecycle()

    WidgetShell(isEditMode = isEditMode, wiggleAngle = wiggleAngle, onRemove = onRemove, modifier = modifier) {
        if (activities.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(text = "Activities", style = TypographyTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(text = "No activities recorded", style = TypographyMeta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            val first = activities.first()
            val displayName = first.userCategory?.toActivityLabel() ?: first.deviceName
            val duration = formatWidgetDuration(first.durationMinutes)
            Card(
                onClick = onTap,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(text = "Activities", style = TypographyTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(text = duration, style = TypographyMeta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (first.userCategory != null) {
                            ActivityCategoryChip(first.userCategory)
                        }
                    }
                    if (activities.size > 1) {
                        Text(
                            text = "+${activities.size - 1} more",
                            style = TypographyMeta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityCategoryChip(category: UserCategory) {
    Card(
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Text(
            text = category.toActivityLabel(),
            style = TypographyMeta,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private fun UserCategory.toActivityLabel(): String = when (this) {
    UserCategory.TRAINING -> "Training"
    UserCategory.LIFE     -> "Life"
    UserCategory.RACE     -> "Race"
}
