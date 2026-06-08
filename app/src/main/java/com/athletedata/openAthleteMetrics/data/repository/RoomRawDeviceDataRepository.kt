package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.RawDeviceDataDao
import com.athletedata.openAthleteMetrics.data.db.toEntity
import com.athletedata.openAthleteMetrics.data.db.toModel
import com.athletedata.openAthleteMetrics.data.model.RawPayload
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomRawDeviceDataRepository @Inject constructor(
    private val dao: RawDeviceDataDao,
) : RawDeviceDataRepository {

    override suspend fun insertAll(payloads: List<RawPayload>, syncSessionId: Long) =
        dao.insertAll(payloads.map { it.toEntity(syncSessionId) })

    override suspend fun getForSession(syncSessionId: Long): List<RawPayload> =
        dao.getForSession(syncSessionId).map { it.toModel() }

    override suspend fun deleteForSession(syncSessionId: Long) =
        dao.deleteForSession(syncSessionId)

    override suspend fun deleteOlderThan(threshold: Instant) =
        dao.deleteOlderThan(threshold.toEpochMilli())
}
