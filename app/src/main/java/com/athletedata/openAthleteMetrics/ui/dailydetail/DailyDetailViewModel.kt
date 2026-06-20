package com.athletedata.openAthleteMetrics.ui.dailydetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.athletedata.openAthleteMetrics.data.db.ActiveCalorieReadingEntity
import com.athletedata.openAthleteMetrics.data.db.HrReadingEntity
import com.athletedata.openAthleteMetrics.data.db.HrvReadingEntity
import com.athletedata.openAthleteMetrics.data.db.RespirationReadingEntity
import com.athletedata.openAthleteMetrics.data.db.SpO2ReadingEntity
import com.athletedata.openAthleteMetrics.data.db.SleepStageEntity
import com.athletedata.openAthleteMetrics.data.db.StepsReadingEntity
import com.athletedata.openAthleteMetrics.data.db.TotalCalorieReadingEntity
import com.athletedata.openAthleteMetrics.data.model.Activity
import com.athletedata.openAthleteMetrics.data.model.QuestionCategory
import com.athletedata.openAthleteMetrics.data.model.QuestionDefinition
import com.athletedata.openAthleteMetrics.data.model.QuestionResponse
import com.athletedata.openAthleteMetrics.data.model.QuestionType
import com.athletedata.openAthleteMetrics.data.model.SleepSession
import com.athletedata.openAthleteMetrics.data.model.UserCategory
import com.athletedata.openAthleteMetrics.data.repository.ActiveCalorieReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.ActivityRepository
import com.athletedata.openAthleteMetrics.data.repository.DailyContextRepository
import com.athletedata.openAthleteMetrics.data.repository.DailySummaryRepository
import com.athletedata.openAthleteMetrics.data.repository.HrReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.HrvReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.QuestionRepository
import com.athletedata.openAthleteMetrics.data.repository.RespirationReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.SettingsRepository
import com.athletedata.openAthleteMetrics.data.repository.SleepRepository
import com.athletedata.openAthleteMetrics.data.repository.SleepStageRepository
import com.athletedata.openAthleteMetrics.data.repository.SpO2ReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.StepsReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.TotalCalorieReadingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class DailyDetailViewModel @Inject constructor(
    private val summaryRepo: DailySummaryRepository,
    private val contextRepo: DailyContextRepository,
    private val activityRepo: ActivityRepository,
    private val questionRepo: QuestionRepository,
    private val sleepRepo: SleepRepository,
    private val sleepStageRepo: SleepStageRepository,
    private val hrRepo: HrReadingRepository,
    private val hrvRepo: HrvReadingRepository,
    private val spo2Repo: SpO2ReadingRepository,
    private val respirationRepo: RespirationReadingRepository,
    private val stepsRepo: StepsReadingRepository,
    private val activeCalRepo: ActiveCalorieReadingRepository,
    private val totalCalRepo: TotalCalorieReadingRepository,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    private val _localDate = MutableStateFlow(LocalDate.now())
    val localDate: StateFlow<LocalDate> = _localDate.asStateFlow()

    val expandedTiles = MutableStateFlow<Set<String>>(emptySet())
    val isEditMode = MutableStateFlow(false)
    private val _optimisticTileOrder = MutableStateFlow<List<TileConfig>?>(null)

    fun setDate(date: LocalDate) { _localDate.value = date }

    fun stepDate(forward: Boolean) {
        val today = LocalDate.now()
        _localDate.update { if (forward) it.plusDays(1).coerceAtMost(today) else it.minusDays(1) }
    }

    fun toggleTile(id: String) {
        expandedTiles.update { current ->
            if (id in current) current - id else current + id
        }
    }

    fun toggleEditMode() {
        val entering = !isEditMode.value
        isEditMode.value = entering
        if (!entering) {
            val pending = _optimisticTileOrder.value
            if (pending != null) {
                viewModelScope.launch { settingsRepo.setDailyDetailTileConfig(pending) }
                _optimisticTileOrder.value = null
            }
        }
    }

    fun onTileReordered(fromIndex: Int, toIndex: Int) {
        val current = (_optimisticTileOrder.value
            ?: (uiState.value as? DailyDetailUiState.Success)?.tileOrder
            ?: DEFAULT_TILE_ORDER).toMutableList()
        if (fromIndex !in current.indices || toIndex !in current.indices) return
        val moved = current.removeAt(fromIndex)
        current.add(toIndex, moved)
        _optimisticTileOrder.value = current.mapIndexed { i, t -> t.copy(sortOrder = i) }
    }

    fun toggleTileVisibility(id: String) {
        val current = (_optimisticTileOrder.value
            ?: (uiState.value as? DailyDetailUiState.Success)?.tileOrder
            ?: DEFAULT_TILE_ORDER).toMutableList()
        val idx = current.indexOfFirst { it.id == id }
        if (idx == -1) return
        val updated = current.mapIndexed { i, t -> if (i == idx) t.copy(isVisible = !t.isVisible) else t }
        _optimisticTileOrder.value = updated
        viewModelScope.launch { settingsRepo.setDailyDetailTileConfig(updated) }
    }

    fun updateActivityCategory(id: Long, category: UserCategory, notes: String?) {
        viewModelScope.launch { activityRepo.updateCategoryAndNotes(id, category, notes) }
    }

    private data class QuestionsBundle(
        val responses: List<QuestionResponse>,
        val definitions: List<QuestionDefinition>,
    )

    val uiState: StateFlow<DailyDetailUiState> = _localDate
        .flatMapLatest { date ->
            val startMs = date.toEpochMilli()
            val endMs = date.plusDays(1).toEpochMilli()

            val questionsFlow = combine(
                questionRepo.getResponsesForDate(date),
                questionRepo.getLifestyleQuestions(),
                questionRepo.getCustomQuestions(),
            ) { responses, lifestyle, custom ->
                QuestionsBundle(responses, lifestyle + custom)
            }

            val sleepWithStagesFlow = sleepRepo.getSessionForDate(date)
                .flatMapLatest { session ->
                    if (session == null) flowOf(null as Pair<SleepSession, List<SleepStageEntity>>?)
                    else flow {
                        val stages = sleepStageRepo.getStagesForSessionOnce(session.id)
                        emit(Pair(session, stages))
                    }
                }

            val rawReadingsFlow: Flow<RawReadingsForDay> = flow {
                emit(loadRawReadings(startMs, endMs))
            }

            val tileConfigFlow = combine(
                settingsRepo.getDailyDetailTileConfig(),
                _optimisticTileOrder,
            ) { persisted, optimistic -> optimistic ?: persisted }

            val primaryFlow = combine(
                summaryRepo.getSummaryForDate(date),
                contextRepo.getForDate(date),
                activityRepo.getActivitiesForDate(date),
                questionsFlow,
            ) { summary, context, activities, questions ->
                PrimaryData(summary, context, activities, questions)
            }

            val secondaryFlow = combine(
                sleepWithStagesFlow,
                rawReadingsFlow,
                tileConfigFlow,
            ) { sleepWithStages, rawReadings, tileConfig ->
                SecondaryData(sleepWithStages, rawReadings, tileConfig)
            }

            combine(primaryFlow, secondaryFlow) { primary, secondary ->
                val summary = primary.summary
                val context = primary.context

                val cardio = if (summary != null && (
                    summary.avgHrBpm != null || summary.restingHrBpm != null ||
                    summary.morningHrvMs != null || summary.avgSpo2Pct != null
                )) {
                    CardiovascularData(
                        avgHrBpm = summary.avgHrBpm,
                        restingHrBpm = summary.restingHrBpm,
                        morningHrvMs = summary.morningHrvMs,
                        avgHrvMs = summary.avgHrvMs,
                        hrvMinMs = summary.hrvMinMs,
                        hrvMaxMs = summary.hrvMaxMs,
                        avgSpo2Pct = summary.avgSpo2Pct,
                        spo2MinPct = summary.spo2MinPct,
                        spo2MaxPct = summary.spo2MaxPct,
                    )
                } else null

                val sleepData = secondary.sleepWithStages?.let { (session, stages) ->
                    val totalMinutes = session.durationMinutes
                    SleepData(
                        formattedDuration = formatDuration(totalMinutes),
                        totalMinutes = totalMinutes,
                        deepMinutes = summary?.sleepDeepMinutes,
                        lightMinutes = summary?.sleepLightMinutes,
                        remMinutes = summary?.sleepRemMinutes,
                        awakeMinutes = summary?.sleepAwakeMinutes,
                        sleepStartMs = session.sleepStartMs.toEpochMilli(),
                        sleepEndMs = session.sleepEndMs.toEpochMilli(),
                        hypnogramSegments = stages.sortedBy { it.startMs }.map { s ->
                            HypnogramSegment(s.stage, s.startMs, s.endMs)
                        },
                    )
                } ?: summary?.sleepMinutes?.let { minutes ->
                    SleepData(
                        formattedDuration = formatDuration(minutes),
                        totalMinutes = minutes,
                        deepMinutes = summary.sleepDeepMinutes,
                        lightMinutes = summary.sleepLightMinutes,
                        remMinutes = summary.sleepRemMinutes,
                        awakeMinutes = summary.sleepAwakeMinutes,
                        sleepStartMs = null,
                        sleepEndMs = null,
                        hypnogramSegments = emptyList(),
                    )
                }

                val activityData = if (summary != null && (
                    summary.steps != null || summary.activeCalories != null || summary.stepsActiveMinutes != null
                )) {
                    ActivityData(
                        steps = summary.steps,
                        activeCalories = summary.activeCalories,
                        exerciseMinutes = summary.stepsActiveMinutes,
                    )
                } else null

                val bodyData = if (context?.weightKg != null || summary?.respirationAvg != null ||
                    summary?.totalCalories != null || context?.bodyFatPct != null) {
                    BodyData(
                        weightKg = context?.weightKg,
                        bodyFatPct = context?.bodyFatPct,
                        respirationAvg = summary?.respirationAvg,
                        totalCalories = summary?.totalCalories,
                    )
                } else null

                val contextScores = if (context != null && (
                    context.fatigue != null || context.stress != null ||
                    context.motivation != null || context.sleepQuality != null ||
                    context.performanceFeel != null
                )) {
                    ContextScores(
                        fatigue = context.fatigue,
                        stress = context.stress,
                        motivation = context.motivation,
                        sleepQuality = context.sleepQuality,
                        performanceFeel = context.performanceFeel,
                    )
                } else null

                DailyDetailUiState.Success(
                    date = date,
                    tileOrder = secondary.tileConfig,
                    cardiovascular = cardio,
                    sleep = sleepData,
                    activity = activityData,
                    body = bodyData,
                    questionGroups = buildQuestionGroups(primary.questions.responses, primary.questions.definitions),
                    isIll = context?.isIll == true,
                    illnessNotes = context?.illnessNotes,
                    habitsJson = context?.habitsJson,
                    contextScores = contextScores,
                    activities = primary.activities.map { it.toUiItem() },
                    rawReadings = secondary.rawReadings,
                ) as DailyDetailUiState
            }.catch { e ->
                emit(DailyDetailUiState.Error(e.message ?: "Unknown error"))
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DailyDetailUiState.Loading,
        )

    private suspend fun loadRawReadings(startMs: Long, endMs: Long): RawReadingsForDay =
        coroutineScope {
            val hr = async { hrRepo.getReadingsInRangeOnce(startMs, endMs) }
            val hrv = async { hrvRepo.getReadingsInRangeOnce(startMs, endMs) }
            val spo2 = async { spo2Repo.getReadingsInRangeOnce(startMs, endMs) }
            val resp = async { respirationRepo.getReadingsInRangeOnce(startMs, endMs) }
            val steps = async { stepsRepo.getReadingsInRangeOnce(startMs, endMs) }
            val activeCal = async { activeCalRepo.getReadingsInRangeOnce(startMs, endMs) }
            val totalCal = async { totalCalRepo.getReadingsInRangeOnce(startMs, endMs) }
            RawReadingsForDay(
                hrReadings = hr.await().map { it.toTimestamped() },
                hrvReadings = hrv.await().map { it.toTimestamped() },
                spo2Readings = spo2.await().map { it.toTimestamped() },
                respirationReadings = resp.await().map { it.toTimestamped() },
                stepsReadings = steps.await().map { it.toTimestamped() },
                activeCalorieReadings = activeCal.await().map { it.toTimestamped() },
                totalCalorieReadings = totalCal.await().map { it.toTimestamped() },
            )
        }

    private fun Activity.toUiItem() = ActivityUiItem(
        id = id,
        deviceName = deviceName,
        userCategory = userCategory,
        formattedDuration = formatDuration(durationMinutes),
        avgHrBpm = avgHrBpm,
        notes = notes,
    )

    private fun buildQuestionGroups(
        responses: List<QuestionResponse>,
        definitions: List<QuestionDefinition>,
    ): List<QuestionGroup> {
        val defMap = definitions.associateBy { it.id }
        data class Row(val name: String, val category: QuestionCategory, val value: String)
        val rows = responses.mapNotNull { r ->
            val def = defMap[r.questionId] ?: return@mapNotNull null
            Row(def.name, def.category, formatResponseValue(r.value, def.type))
        }
        return rows
            .groupBy { it.category }
            .entries
            .sortedBy { (cat, _) -> if (cat == QuestionCategory.LIFESTYLE) 0 else 1 }
            .map { (cat, grouped) ->
                QuestionGroup(
                    category = cat,
                    items = grouped.map { QuestionItem(it.name, it.value) },
                )
            }
    }

    private fun formatResponseValue(value: String, type: QuestionType): String = when (type) {
        QuestionType.BOOLEAN -> if (value == "1") "Yes" else "No"
        QuestionType.SCALE   -> value
        QuestionType.TEXT    -> value
    }

    private data class PrimaryData(
        val summary: com.athletedata.openAthleteMetrics.data.model.DailySummary?,
        val context: com.athletedata.openAthleteMetrics.data.model.DailyContext?,
        val activities: List<Activity>,
        val questions: QuestionsBundle,
    )

    private data class SecondaryData(
        val sleepWithStages: Pair<SleepSession, List<SleepStageEntity>>?,
        val rawReadings: RawReadingsForDay,
        val tileConfig: List<TileConfig>,
    )
}

private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun Instant.toLocalTimeLabel(): String =
    atZone(ZoneId.systemDefault()).format(TIME_FMT)

private fun HrReadingEntity.toTimestamped() =
    TimestampedReading(recordedAt.toLocalTimeLabel(), bpm.toString(), "bpm")

private fun HrvReadingEntity.toTimestamped() =
    TimestampedReading(recordedAt.toLocalTimeLabel(), "%.1f".format(rmssdMs), "ms")

private fun SpO2ReadingEntity.toTimestamped() =
    TimestampedReading(recordedAt.toLocalTimeLabel(), "%.1f".format(percentage), "%")

private fun RespirationReadingEntity.toTimestamped() =
    TimestampedReading(recordedAt.toLocalTimeLabel(), "%.1f".format(breathsPerMinute), "brpm")

private fun StepsReadingEntity.toTimestamped() =
    TimestampedReading(recordedAt.toLocalTimeLabel(), cumulativeSteps.toString(), "steps")

private fun ActiveCalorieReadingEntity.toTimestamped() =
    TimestampedReading(recordedAt.toLocalTimeLabel(), "%.0f".format(calories), "kcal")

private fun TotalCalorieReadingEntity.toTimestamped() =
    TimestampedReading(recordedAt.toLocalTimeLabel(), "%.0f".format(calories), "kcal")

private fun LocalDate.toEpochMilli(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun formatDuration(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "${m}m"
        m == 0 -> "${h}h"
        else   -> "${h}h ${m}m"
    }
}
