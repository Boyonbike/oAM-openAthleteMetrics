package com.athletedata.openAthleteMetrics.ui.overview.widgets

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.athletedata.openAthleteMetrics.data.model.QuestionType
import com.athletedata.openAthleteMetrics.data.model.WidgetSize
import com.athletedata.openAthleteMetrics.data.repository.QuestionRepository
import com.athletedata.openAthleteMetrics.ui.theme.TypographyMeta
import com.athletedata.openAthleteMetrics.ui.theme.TypographyTitle
import com.athletedata.openAthleteMetrics.ui.theme.TypographyValue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

data class StarredHabitItem(
    val questionId: Long,
    val name: String,
    val type: QuestionType,
    val value: String?,
)

data class StarredHabitsBarUiState(
    val items: List<StarredHabitItem> = emptyList(),
)

@HiltViewModel
class StarredHabitsBarViewModel @Inject constructor(
    private val questionRepo: QuestionRepository,
) : ViewModel() {

    private val _date = MutableStateFlow(LocalDate.now())

    fun setDate(date: LocalDate) { _date.value = date }

    val uiState: StateFlow<StarredHabitsBarUiState> = _date.flatMapLatest { date ->
        combine(
            questionRepo.getCustomQuestions(),
            questionRepo.getResponsesForDate(date),
        ) { questions, responses ->
            val responseMap = responses.associateBy { it.questionId }
            StarredHabitsBarUiState(
                items = questions.map { q ->
                    StarredHabitItem(q.id, q.name, q.type, responseMap[q.id]?.value)
                },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StarredHabitsBarUiState())
}

@Composable
fun StarredHabitsBarWidget(
    widgetId: Long,
    date: LocalDate,
    size: WidgetSize,
    isEditMode: Boolean,
    wiggleAngle: Float,
    onTap: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StarredHabitsBarViewModel = hiltViewModel(key = "habits_bar_widget_$widgetId"),
) {
    LaunchedEffect(date) { viewModel.setDate(date) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var expandedTextItem by remember { mutableStateOf<StarredHabitItem?>(null) }

    WidgetShell(isEditMode = isEditMode, wiggleAngle = wiggleAngle, onRemove = onRemove, modifier = modifier) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = "Habits", style = TypographyTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (uiState.items.isEmpty()) {
                    Text(
                        text = "No habits yet — add them in the Habits tab",
                        style = TypographyMeta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { onTap() },
                    )
                } else {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        uiState.items.forEach { item ->
                            val isAnswered = item.value != null
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        when {
                                            item.type == QuestionType.TEXT && isAnswered -> expandedTextItem = item
                                            else -> onTap()
                                        }
                                    }
                                    .padding(vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                when (item.type) {
                                    QuestionType.SCALE -> {
                                        val intValue = item.value?.toIntOrNull()
                                        if (intValue != null) {
                                            Row {
                                                Text(text = "$intValue", style = TypographyValue, modifier = Modifier.alignByBaseline())
                                                Text(
                                                    text = "/5",
                                                    style = TypographyMeta,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.alignByBaseline(),
                                                )
                                            }
                                        } else {
                                            Text(text = "--", style = TypographyValue)
                                        }
                                    }
                                    QuestionType.BOOLEAN -> {
                                        Text(
                                            text = when (item.value) {
                                                "1"  -> "Yes"
                                                "0"  -> "No"
                                                else -> "--"
                                            },
                                            style = TypographyValue,
                                        )
                                    }
                                    QuestionType.TEXT -> {
                                        if (item.value != null) {
                                            Text(
                                                text = item.value,
                                                style = TypographyMeta,
                                                textAlign = TextAlign.Center,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        } else {
                                            Text(text = "--", style = TypographyValue)
                                        }
                                    }
                                }
                                Text(
                                    text = item.name,
                                    style = TypographyMeta,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    expandedTextItem?.let { item ->
        Dialog(onDismissRequest = { expandedTextItem = null }) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(text = item.name, style = TypographyTitle, color = MaterialTheme.colorScheme.onSurface)
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(6.dp))
                    Text(text = item.value ?: "", style = MaterialTheme.typography.bodyMedium)
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp))
                    androidx.compose.material3.TextButton(
                        onClick = { expandedTextItem = null },
                        modifier = Modifier.align(Alignment.End),
                    ) { Text("Close") }
                }
            }
        }
    }
}
