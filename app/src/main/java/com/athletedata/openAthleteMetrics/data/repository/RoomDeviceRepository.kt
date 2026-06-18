package com.athletedata.openAthleteMetrics.data.repository

import androidx.room.withTransaction
import com.athletedata.openAthleteMetrics.data.db.AppDatabase
import com.athletedata.openAthleteMetrics.data.db.DeviceDao
import com.athletedata.openAthleteMetrics.data.db.RawDeviceDataDao
import com.athletedata.openAthleteMetrics.data.db.SyncSessionDao
import com.athletedata.openAthleteMetrics.data.db.toEntity
import com.athletedata.openAthleteMetrics.data.db.toModel
import com.athletedata.openAthleteMetrics.data.model.Device
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomDeviceRepository @Inject constructor(
    private val database: AppDatabase,
    private val dao: DeviceDao,
    private val syncSessionDao: SyncSessionDao,
    private val rawDeviceDataDao: RawDeviceDataDao,
) : DeviceRepository {

    override suspend fun upsert(device: Device) {
        try {
            dao.upsert(device.toEntity())
        } catch (e: Exception) {
            Timber.e(e, "Failed to upsert device")
            throw e
        }
    }

    override suspend fun delete(device: Device) {
        try {
            database.withTransaction {
                rawDeviceDataDao.deleteForDevice(device.id)
                syncSessionDao.deleteForDevice(device.id)
                dao.delete(device.toEntity())
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete device")
            throw e
        }
    }

    override fun getAllDevices(): Flow<List<Device>> =
        dao.getAllDevices().map { entities -> entities.map { it.toModel() } }

    override suspend fun getDeviceById(id: Long): Device? =
        dao.getById(id)?.toModel()

    override suspend fun getDeviceByAddress(bleAddress: String): Device? =
        dao.getDeviceByAddress(bleAddress)?.toModel()

    override suspend fun updateLastSeen(bleAddress: String, timestampMs: Long) {
        try {
            dao.updateLastSeen(bleAddress, timestampMs)
        } catch (e: Exception) {
            Timber.e(e, "Failed to update device last seen")
            throw e
        }
    }

    override suspend fun updateLastSync(bleAddress: String, timestampMs: Long) {
        try {
            dao.updateLastSync(bleAddress, timestampMs)
        } catch (e: Exception) {
            Timber.e(e, "Failed to update device last sync")
            throw e
        }
    }

    override suspend fun updateLastBatteryPct(bleAddress: String, pct: Int) {
        try {
            dao.updateLastBatteryPct(bleAddress, pct)
        } catch (e: Exception) {
            Timber.e(e, "Failed to update device battery pct")
            throw e
        }
    }
}
