package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.SyncSessionDao
import com.athletedata.openAthleteMetrics.data.db.toEntity
import com.athletedata.openAthleteMetrics.data.db.toModel
import com.athletedata.openAthleteMetrics.data.model.SyncSession
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomSyncSessionRepository @Inject constructor(
    private val dao: SyncSessionDao,
) : SyncSessionRepository {

    override suspend fun insert(session: SyncSession): Long {
        return try {
            dao.insert(session.toEntity())
        } catch (e: Exception) {
            Timber.e(e, "Failed to insert sync session")
            throw e
        }
    }

    override suspend fun update(session: SyncSession) {
        try {
            dao.upsert(session.toEntity())
        } catch (e: Exception) {
            Timber.e(e, "Failed to update sync session")
            throw e
        }
    }

    override fun getSessionsForDevice(deviceId: Long): Flow<List<SyncSession>> =
        dao.getSessionsForDevice(deviceId).map { entities -> entities.map { it.toModel() } }

    override suspend fun getLatestSessionForDevice(deviceId: Long): SyncSession? =
        dao.getLatestSessionForDevice(deviceId)?.toModel()

    override suspend fun deleteOlderThan(threshold: Instant) {
        try {
            dao.deleteOlderThan(threshold.toEpochMilli())
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete old sync sessions")
            throw e
        }
    }

    override suspend fun markOldInProgressAsFailed(threshold: Instant) {
        try {
            dao.markOldInProgressAsFailed(threshold.toEpochMilli())
        } catch (e: Exception) {
            Timber.e(e, "Failed to mark old in-progress sync sessions as failed")
            throw e
        }
    }

    override suspend fun getById(id: Long): SyncSession? =
        dao.getById(id)?.toModel()

    override suspend fun getRecentInProgress(since: Instant): List<SyncSession> =
        dao.getRecentInProgress(since.toEpochMilli()).map { it.toModel() }
}
