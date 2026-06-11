package com.athletedata.openAthleteMetrics.ble.sync

import com.athletedata.openAthleteMetrics.data.model.SleepSession
import org.json.JSONArray
import timber.log.Timber
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Merges a list of [SleepSession]s that may span multiple syncs for the same
 * (driverId, date) pair into one canonical session per date.
 *
 * Package-internal so both [DeviceSyncProcessor] and [DeviceReprocessor] can use it.
 */
internal fun mergeSleepSessions(sessions: List<SleepSession>): List<SleepSession> {
    if (sessions.isEmpty()) return sessions
    return sessions
        .groupBy { Pair(it.driverId, it.date) }
        .values
        .map { group ->
            val start             = group.minOf { it.sleepStartMs }
            val sessionEnd        = group.maxOf { it.sleepEndMs }
            val startEpoch        = start.toEpochMilli()
            val sessionEndEpoch   = sessionEnd.toEpochMilli()
            val validStageNames   = setOf("DEEP", "LIGHT", "REM", "AWAKE")

            val allStageObjects = group
                .mapNotNull { s -> s.stagesJson?.let { json -> Pair(s.date.toString(), json) } }
                .flatMap { (dateIso, json) ->
                    runCatching {
                        val arr = JSONArray(json)
                        (0 until arr.length()).mapNotNull { i ->
                            val obj       = arr.getJSONObject(i)
                            val stageName = runCatching { obj.getString("stage") }.getOrNull()
                            val sMs       = runCatching { obj.getLong("startMs") }.getOrNull()
                            val eMs       = runCatching { obj.getLong("endMs") }.getOrNull()
                            when {
                                stageName == null || stageName !in validStageNames -> {
                                    Timber.w("Discarding stage with invalid name '$stageName' for $dateIso")
                                    null
                                }
                                sMs == null || eMs == null -> {
                                    Timber.w("Discarding stage missing startMs/endMs for $dateIso")
                                    null
                                }
                                sMs >= eMs -> {
                                    Timber.w("Discarding stage where startMs >= endMs for $dateIso")
                                    null
                                }
                                sMs < startEpoch || eMs > sessionEndEpoch + 43_200_000L -> {
                                    Timber.w("Discarding stage outside session bounds for $dateIso: sMs=$sMs eMs=$eMs")
                                    null
                                }
                                else -> obj
                            }
                        }
                    }.getOrElse { e ->
                        Timber.w("Discarding malformed stagesJson for $dateIso: ${e.message}")
                        emptyList()
                    }
                }

            // Deduplicate by (stage, startMs), keeping the entry with the larger endMs.
            val deduplicatedStages = allStageObjects
                .groupBy { Pair(it.getString("stage"), it.getLong("startMs")) }
                .values
                .map { entries -> entries.maxByOrNull { it.getLong("endMs") }!! }
                .sortedBy { it.getLong("startMs") }

            // Extend end to include AWAKE stage blocks that lie past the reported sleepEndMs.
            val stageMaxEndMs = deduplicatedStages.maxOfOrNull { it.getLong("endMs") } ?: 0L
            val end = if (stageMaxEndMs > sessionEndEpoch)
                Instant.ofEpochMilli(stageMaxEndMs)
            else sessionEnd

            val mergedStagesJson = if (deduplicatedStages.isEmpty()) null
                else JSONArray().also { arr -> deduplicatedStages.forEach { arr.put(it) } }.toString()

            group.first().copy(
                sleepStartMs    = start,
                sleepEndMs      = end,
                durationMinutes = ChronoUnit.MINUTES.between(start, end).toInt(),
                stagesJson      = mergedStagesJson,
            )
        }
}
