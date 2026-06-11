package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.model.SyncSession
import java.time.Instant
import kotlinx.coroutines.flow.Flow

interface SyncSessionRepository {

    suspend fun insert(session: SyncSession): Long

    suspend fun update(session: SyncSession)

    fun getSessionsForDevice(deviceId: Long): Flow<List<SyncSession>>

    suspend fun getLatestSessionForDevice(deviceId: Long): SyncSession?

    suspend fun deleteOlderThan(threshold: Instant)

    suspend fun markOldPartialsAsFailed(threshold: Instant)

    suspend fun getById(id: Long): SyncSession?

    suspend fun getRecentPartial(since: Instant): List<SyncSession>
}
