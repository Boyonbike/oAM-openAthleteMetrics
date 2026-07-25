package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.model.Device
import kotlinx.coroutines.flow.Flow

interface DeviceRepository {

    suspend fun upsert(device: Device)

    suspend fun delete(device: Device)

    fun getAllDevices(): Flow<List<Device>>

    suspend fun getDeviceById(id: Long): Device?

    suspend fun getDeviceByAddress(bleAddress: String): Device?

    suspend fun updateLastSeen(bleAddress: String, timestampMs: Long)

    suspend fun updateLastSync(bleAddress: String, timestampMs: Long)

    suspend fun updateLastBatteryPct(bleAddress: String, pct: Int)

    suspend fun setPrimary(deviceId: Long)

    suspend fun setAutoSync(deviceId: Long, enabled: Boolean)

    suspend fun setCdmAssociated(bleAddress: String, associated: Boolean)

    fun observePrimary(): Flow<Device?>

    suspend fun getPrimary(): Device?

    suspend fun getAutoSyncEnabledOrdered(): List<Device>

    // RESET-SYSTEM
    suspend fun deleteAll()
}
