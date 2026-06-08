package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.model.SyncSession
import kotlinx.coroutines.flow.Flow

interface SyncSessionRepository {

    suspend fun insert(session: SyncSession): Long

    suspend fun update(session: SyncSession)

    fun getSessionsForDevice(deviceId: Long): Flow<List<SyncSession>>

    suspend fun getLatestSessionForDevice(deviceId: Long): SyncSession?
}
