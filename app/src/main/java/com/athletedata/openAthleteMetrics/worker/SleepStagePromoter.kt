package com.athletedata.openAthleteMetrics.worker

import com.athletedata.openAthleteMetrics.data.db.SleepStageEntity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.data.model.SleepSession
import com.athletedata.openAthleteMetrics.data.model.SleepStage
import com.athletedata.openAthleteMetrics.data.repository.MetricReadingStagingRepository
import com.athletedata.openAthleteMetrics.data.repository.SleepRepository
import com.athletedata.openAthleteMetrics.data.repository.SleepStageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Minimum gap, in milliseconds, between the end of one stage record and the start of the
 * next for them to be treated as two separate sleep periods rather than one continuous
 * night. Matches the Hume Band 1 BLE protocol's own session-grouping convention (see
 * "Driver Builds/Hume Band 1/Hume Band 1 BLE Protocol.md": a gap >= 3600 seconds between
 * items ends one sleep period and starts the next).
 */
private const val SESSION_GAP_THRESHOLD_MS = 60 * 60 * 1000L

data class SleepPromotionResult(
    val datesProcessed: List<LocalDate>,
    val stagesInserted: Int,
    val sessionsCreated: Int,
    val errors: List<String>,
)

@Singleton
class SleepStagePromoter @Inject constructor(
    private val stagingRepository: MetricReadingStagingRepository,
    private val sleepRepository: SleepRepository,
    private val sleepStageRepository: SleepStageRepository,
) {
    suspend fun promote(
        driverId: String,
        syncWindowStartMs: Long,
        syncWindowEndMs: Long,
    ): SleepPromotionResult = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()

        val pendingRows = stagingRepository.getPendingSleepStages(
            source = DataSource.DEVICE,
            driverId = driverId,
            syncWindowStartMs = syncWindowStartMs,
            syncWindowEndMs = syncWindowEndMs,
        )

        data class ParsedStage(
            val rowId: Long,
            val stage: SleepStage,
            val startMs: Long,
            val endMs: Long,
        )

        val parsedStages = mutableListOf<ParsedStage>()
        for (row in pendingRows) {
            val rowJson = JSONObject(row.metaJson ?: "{}")
            if (!rowJson.optBoolean("pending_sleep_stage", false)) {
                Timber.w("Skipping staging row id=${row.id}: missing pending_sleep_stage flag in meta_json")
                continue
            }
            val parsed = runCatching {
                ParsedStage(
                    rowId   = row.id,
                    stage   = SleepStage.valueOf(rowJson.getString("stage")),
                    startMs = rowJson.getLong("start_ms"),
                    endMs   = rowJson.getLong("end_ms"),
                )
            }.getOrElse { e ->
                val msg = "Skipping staging row id=${row.id}: malformed meta_json — ${e.message}"
                Timber.w(msg)
                errors.add(msg)
                null
            } ?: continue

            parsedStages.add(parsed)
        }

        // Group stage records into continuous sleep periods rather than bucketing each
        // record by its own calendar date — a night crossing local midnight must stay one
        // session. A new group starts whenever the gap since the previous record's end
        // reaches the threshold above.
        val sessionGroups = mutableListOf<MutableList<ParsedStage>>()
        for (stage in parsedStages.sortedBy { it.startMs }) {
            val previousStage = sessionGroups.lastOrNull()?.last()
            if (previousStage != null && stage.startMs - previousStage.endMs < SESSION_GAP_THRESHOLD_MS) {
                sessionGroups.last().add(stage)
            } else {
                sessionGroups.add(mutableListOf(stage))
            }
        }

        var stagesInserted = 0
        var sessionsCreated = 0
        val datesProcessed = mutableListOf<LocalDate>()
        val promotedRowIds = mutableListOf<Long>()

        for (stages in sessionGroups) {
            val startMs = stages.minOf { it.startMs }
            val endMs = stages.maxOf { it.endMs }
            // Per the documented SleepSession.date contract, a session is dated by the
            // calendar date of the morning the sleeper woke up — i.e. the local date of
            // this group's end, not its start.
            val date = Instant.ofEpochMilli(endMs).atZone(ZoneId.systemDefault()).toLocalDate()

            val sessionId = runCatching {
                val existing = sleepRepository.getByDriverAndDate(driverId, date)
                if (existing != null) {
                    existing.id
                } else {
                    sleepRepository.insert(
                        SleepSession(
                            date            = date,
                            sleepStartMs    = Instant.ofEpochMilli(startMs),
                            sleepEndMs      = Instant.ofEpochMilli(endMs),
                            durationMinutes = ((endMs - startMs) / 60_000L).toInt(),
                            source          = DataSource.DEVICE,
                            driverId        = driverId,
                        )
                    )
                    sessionsCreated++

                    // Re-query to obtain the auto-generated session id.
                    sleepRepository.getByDriverAndDate(driverId, date)!!.id
                }
            }.getOrElse { e ->
                val msg = "Failed to upsert sleep session for $date: ${e.message}"
                Timber.e(e, msg)
                errors.add(msg)
                null
            } ?: continue

            val entities = stages.map { s ->
                SleepStageEntity(
                    sessionId         = sessionId,
                    stage             = s.stage,
                    startMs           = s.startMs,
                    endMs             = s.endMs,
                    durationMinutes   = ((s.endMs - s.startMs) / 60_000L).toInt(),
                    source            = DataSource.DEVICE,
                    driverId          = driverId,
                    computedByVersion = 1,
                )
            }
            val rowIds = sleepStageRepository.insertAllOrIgnore(entities)
            stagesInserted += rowIds.count { it != -1L }
            promotedRowIds.addAll(stages.map { it.rowId })
            datesProcessed.add(date)
        }

        if (promotedRowIds.isNotEmpty()) {
            stagingRepository.deleteByIds(promotedRowIds)
        }

        SleepPromotionResult(
            // A same-calendar-date nap and overnight session both resolve to one date;
            // dedupe so callers see each touched date exactly once.
            datesProcessed = datesProcessed.distinct(),
            stagesInserted = stagesInserted,
            sessionsCreated = sessionsCreated,
            errors = errors,
        )
    }
}
