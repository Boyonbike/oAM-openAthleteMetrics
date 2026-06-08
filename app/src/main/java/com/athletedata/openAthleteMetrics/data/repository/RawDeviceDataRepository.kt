package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.model.RawPayload
import java.time.Instant

interface RawDeviceDataRepository {

    suspend fun insertAll(payloads: List<RawPayload>, syncSessionId: Long)

    suspend fun getForSession(syncSessionId: Long): List<RawPayload>

    suspend fun deleteForSession(syncSessionId: Long)

    suspend fun deleteOlderThan(threshold: Instant)
}
