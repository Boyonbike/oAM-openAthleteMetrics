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
 * night. This is a deliberate app-level policy decision about what counts as "the same
 * sleep session" — independent of any specific device or protocol — applied uniformly
 * across drivers unless a driver overrides it via its manifest's sessionGapThresholdMs
 * field (see WasmDriverManifest).
 */
// CHANGED: comment rewritten as app policy; no longer attributes the value to a device protocol doc.
private const val SESSION_GAP_THRESHOLD_MS = 60 * 60 * 1000L

private fun overlapGapMs(aStartMs: Long, aEndMs: Long, bStartMs: Long, bEndMs: Long): Long = when {
    aStartMs > bEndMs -> aStartMs - bEndMs
    aEndMs < bStartMs -> bStartMs - aEndMs
    else -> 0L // overlapping
}

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
        sessionGapThresholdMs: Long? = null, // CHANGED: per-driver override; null -> app default
        deviceId: Long? = null, // physical device (numeric devices.id), not the driver
    ): SleepPromotionResult = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        val effectiveThresholdMs = sessionGapThresholdMs ?: SESSION_GAP_THRESHOLD_MS // CHANGED

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
            if (previousStage != null && stage.startMs - previousStage.endMs < effectiveThresholdMs) { // CHANGED
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
            // this group's end, not its start. This is only a preliminary value: if an
            // earlier, partial promote() call already created a session for this same
            // physical night under a different date (e.g. it only had pre-midnight stages),
            // the lookup below finds it by span proximity rather than by this date, and the
            // date actually persisted may be corrected once the fuller merge is known.
            val date = Instant.ofEpochMilli(endMs).atZone(ZoneId.systemDefault()).toLocalDate()

            var finalDate = date
            val sessionId = runCatching {
                // Search a small window of nearby dates rather than an exact match on `date`:
                // a prior partial call for the same night may have stored it under the
                // adjacent calendar date. A single continuous sleep session can't plausibly
                // shift its own wake-date determination by more than a day between partial
                // batches, so +/-1 day is enough. An exact date match always attaches
                // (preserves the existing same-day-nap-attaches-to-existing-row behaviour,
                // regardless of the gap between them); a match found only via the widened
                // window must additionally be within the gap threshold, or it's just an
                // unrelated session that happens to fall on an adjacent date.
                val candidates = sleepRepository.getSessionsForDriverInRange(
                    driverId, date.minusDays(1), date.plusDays(1),
                )
                val existing = candidates.firstOrNull { it.date == date }
                    ?: candidates
                        .map { candidate ->
                            val gapMs = overlapGapMs(
                                startMs, endMs,
                                candidate.sleepStartMs.toEpochMilli(), candidate.sleepEndMs.toEpochMilli(),
                            )
                            candidate to gapMs
                        }
                        .filter { (_, gapMs) -> gapMs < effectiveThresholdMs }
                        .minByOrNull { (_, gapMs) -> gapMs }
                        ?.first

                if (existing != null) {
                    // A prior promote() call may have created this session from only part of
                    // the night's stages — extend its recorded span (not just insert new stage
                    // rows under its id) so duration_minutes doesn't go stale relative to the
                    // fuller, accumulated stage total DailySummaryWorker will later sum.
                    val existingStartMs = existing.sleepStartMs.toEpochMilli()
                    val existingEndMs = existing.sleepEndMs.toEpochMilli()
                    val gapMs = overlapGapMs(startMs, endMs, existingStartMs, existingEndMs)

                    val mergedStartMs: Long
                    val mergedEndMs: Long
                    val mergedDurationMinutes: Int
                    if (gapMs < effectiveThresholdMs) {
                        // Contiguous with the existing span — safe to fold into one envelope.
                        mergedStartMs = minOf(existingStartMs, startMs)
                        mergedEndMs = maxOf(existingEndMs, endMs)
                        mergedDurationMinutes = ((mergedEndMs - mergedStartMs) / 60_000L).toInt()
                    } else {
                        // Disjoint from the existing span (e.g. an afternoon nap attaching to
                        // an overnight session that resolves to the same wake date) — taking
                        // the envelope here would count the waking hours between them as sleep.
                        // Keep the existing span and add only this group's own stage-covered
                        // minutes.
                        mergedStartMs = existingStartMs
                        mergedEndMs = existingEndMs
                        val groupMinutes = stages.sumOf { (it.endMs - it.startMs) / 60_000L }.toInt()
                        mergedDurationMinutes = existing.durationMinutes + groupMinutes
                    }

                    // The true wake date is the local date of the merged end, which may
                    // correct an earlier partial call's provisional date.
                    finalDate = Instant.ofEpochMilli(mergedEndMs).atZone(ZoneId.systemDefault()).toLocalDate()

                    if (mergedStartMs != existingStartMs ||
                        mergedEndMs != existingEndMs ||
                        mergedDurationMinutes != existing.durationMinutes ||
                        finalDate != existing.date
                    ) {
                        sleepRepository.updateSessionSpan(
                            id = existing.id,
                            date = finalDate,
                            sleepStartMs = Instant.ofEpochMilli(mergedStartMs),
                            sleepEndMs = Instant.ofEpochMilli(mergedEndMs),
                            durationMinutes = mergedDurationMinutes,
                        )
                    }
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
                            // Physical device (numeric devices.id), not the driver.
                            deviceId        = deviceId,
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
            datesProcessed.add(finalDate)
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
