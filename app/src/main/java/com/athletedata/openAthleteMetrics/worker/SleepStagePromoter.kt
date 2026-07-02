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

        val byDate = mutableMapOf<LocalDate, MutableList<ParsedStage>>()
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

            val date = Instant.ofEpochMilli(parsed.startMs).atZone(ZoneId.systemDefault()).toLocalDate()
            byDate.getOrPut(date) { mutableListOf() }.add(parsed)
        }

        var stagesInserted = 0
        var sessionsCreated = 0
        val datesProcessed = mutableListOf<LocalDate>()
        val promotedRowIds = mutableListOf<Long>()

        for ((date, stages) in byDate) {
            val sessionId = runCatching {
                val existing = sleepRepository.getByDriverAndDate(driverId, date)
                if (existing != null) {
                    existing.id
                } else {
                    val startMs = stages.minOf { it.startMs }
                    val endMs = stages.maxOf { it.endMs }
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
            datesProcessed = datesProcessed,
            stagesInserted = stagesInserted,
            sessionsCreated = sessionsCreated,
            errors = errors,
        )
    }
}
