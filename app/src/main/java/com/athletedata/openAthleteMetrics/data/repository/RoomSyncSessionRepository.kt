package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.SyncSessionDao
import com.athletedata.openAthleteMetrics.data.db.toEntity
import com.athletedata.openAthleteMetrics.data.db.toModel
import com.athletedata.openAthleteMetrics.data.model.SyncSession
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomSyncSessionRepository @Inject constructor(
    private val dao: SyncSessionDao,
) : SyncSessionRepository {

    override suspend fun insert(session: SyncSession): Long = dao.insert(session.toEntity())

    override suspend fun update(session: SyncSession) = dao.update(session.toEntity())

    override fun getSessionsForDevice(deviceId: Long): Flow<List<SyncSession>> =
        dao.getSessionsForDevice(deviceId).map { entities -> entities.map { it.toModel() } }

    override suspend fun getLatestSessionForDevice(deviceId: Long): SyncSession? =
        dao.getLatestSessionForDevice(deviceId)?.toModel()

    override suspend fun deleteOlderThan(threshold: Instant) =
        dao.deleteOlderThan(threshold.toEpochMilli())

    override suspend fun markOldPartialsAsFailed(threshold: Instant) =
        dao.markOldPartialsAsFailed(threshold.toEpochMilli())

    override suspend fun getById(id: Long): SyncSession? =
        dao.getById(id)?.toModel()

    override suspend fun getRecentPartial(since: Instant): List<SyncSession> =
        dao.getRecentPartial(since.toEpochMilli()).map { it.toModel() }
}
