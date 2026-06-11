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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomDeviceRepository @Inject constructor(
    private val database: AppDatabase,
    private val dao: DeviceDao,
    private val syncSessionDao: SyncSessionDao,
    private val rawDeviceDataDao: RawDeviceDataDao,
) : DeviceRepository {

    override suspend fun upsert(device: Device) = dao.upsert(device.toEntity())

    override suspend fun delete(device: Device) {
        database.withTransaction {
            rawDeviceDataDao.deleteForDevice(device.id)
            syncSessionDao.deleteForDevice(device.id)
            dao.delete(device.toEntity())
        }
    }

    override fun getAllDevices(): Flow<List<Device>> =
        dao.getAllDevices().map { entities -> entities.map { it.toModel() } }

    override suspend fun getDeviceById(id: Long): Device? =
        dao.getById(id)?.toModel()

    override suspend fun getDeviceByAddress(bleAddress: String): Device? =
        dao.getDeviceByAddress(bleAddress)?.toModel()

    override suspend fun updateLastSeen(bleAddress: String, timestampMs: Long) =
        dao.updateLastSeen(bleAddress, timestampMs)

    override suspend fun updateLastSync(bleAddress: String, timestampMs: Long) =
        dao.updateLastSync(bleAddress, timestampMs)

    override suspend fun updateLastBatteryPct(bleAddress: String, pct: Int) =
        dao.updateLastBatteryPct(bleAddress, pct)
}
