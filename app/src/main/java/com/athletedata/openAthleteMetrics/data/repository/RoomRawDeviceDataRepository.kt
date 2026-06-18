package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.RawDeviceDataDao
import com.athletedata.openAthleteMetrics.data.db.toEntity
import com.athletedata.openAthleteMetrics.data.db.toModel
import com.athletedata.openAthleteMetrics.data.model.RawPayload
import java.time.Instant
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomRawDeviceDataRepository @Inject constructor(
    private val dao: RawDeviceDataDao,
) : RawDeviceDataRepository {

    override suspend fun insertAll(payloads: List<RawPayload>, syncSessionId: Long) {
        try {
            dao.insertAll(payloads.map { it.toEntity(syncSessionId) })
        } catch (e: Exception) {
            Timber.e(e, "Failed to insert raw device data")
            throw e
        }
    }

    override suspend fun getForSession(syncSessionId: Long): List<RawPayload> =
        dao.getForSession(syncSessionId).map { it.toModel() }

    override suspend fun deleteForSession(syncSessionId: Long) {
        try {
            dao.deleteForSession(syncSessionId)
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete raw device data for session")
            throw e
        }
    }

    override suspend fun deleteOlderThan(threshold: Instant) {
        try {
            dao.deleteOlderThan(threshold.toEpochMilli())
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete old raw device data")
            throw e
        }
    }

    override suspend fun getForDevice(deviceId: Long, since: Instant): List<RawPayload> =
        dao.getForDeviceAfter(deviceId, since.toEpochMilli()).map { it.toModel() }
}
